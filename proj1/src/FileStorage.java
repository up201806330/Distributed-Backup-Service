import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FileStorage implements Serializable {
    /**
     * Singleton instance of FileStorage
     */
    public static FileStorage instance;

    /**
     * Maximum disk space in KBytes that can be used for storing chunks
     */
    public long maximumSpaceAvailable;

    /**
     * Directory where chunks are stored
     */
    public String chunksDir;

    /**
     * Directory where restored files are stored
     */
    public String restoreDir;

    /**
     * Directory where received chunks are temporarily stored to restore files
     */
    public String cacheDir;

    /**
     * Concurrent set of all files whose backup was initiated by this peer
     */
    public final Set<FileObject> initiatedFiles = ConcurrentHashMap.newKeySet();

    /**
     * Concurrent map that for each chunk of each initiated file, has a concurrent set of peers that have that chunk backed up
     */
    public final ConcurrentHashMap<Chunk, ConcurrentHashMap.KeySetView<Object, Boolean>> chunksBackedPeers = new ConcurrentHashMap<>();

    /**
     * Concurrent set of all chunks stored locally by peer
     */
    public final Set<Chunk> storedChunks = ConcurrentHashMap.newKeySet();

    /**
     * Concurrent set of all chunks stored by the system, useful for the backup enhancement
     */
    public final Set<Chunk> allChunks = ConcurrentHashMap.newKeySet();

    /**
     * Concurrent map that for each PeerID, has a concurrent set of filepaths of files initiated by this peer that should have been deleted already. Used by the delete enhancement
     */
    public final ConcurrentHashMap<Integer, ConcurrentHashMap.KeySetView<Object, Boolean>> peersWithDeadChunks = new ConcurrentHashMap<>();

    /**
     * Singleton constructor
     * @throws IOException
     */
    public FileStorage() throws IOException {
        if (FileStorage.instance == null) FileStorage.instance = this;

        maximumSpaceAvailable = 1000000; // = 1000MB = 1GB

        chunksDir = Peer.getServiceDirectory() + "/chunks";
        restoreDir = Peer.getServiceDirectory() + "/restored";
        cacheDir = Peer.getServiceDirectory() + "/cache";

        Files.createDirectories(Paths.get(chunksDir));
        Files.createDirectories(Paths.get(restoreDir));
        Files.createDirectories(Paths.get(cacheDir));
    }

    /**
     * Tries to store new chunk as a file locally, incrementing rep degree either it is stored or not
     * @param chunk
     * @return
     */
    public boolean storeChunk(Chunk chunk) {
        if(!addStoredChunk(chunk)) return false;
        incrementReplicationDegree(chunk);

        FileOutputStream fos;
        try {
            fos = new FileOutputStream( chunksDir + "/" + chunk.getChunkID());
            fos.write(chunk.getContent());
            fos.close();
            return true;
        } catch (IOException e) {
            System.out.println("Error writing chunk to file locally");
            return false;
        }
    }

    /**
     * Gets
     * @param chunk
     * @return perceived replication degree of chunk, be it a stored locally backed up chunk or a part of an initiated file
     */
    public int getPerceivedReplicationDegree(Chunk chunk){
        var repDegreeIfItsInitiatedChunk =
        findInitiatedFile(chunk.getFileID()).
                map(fileParser -> fileParser.findChunk(chunk).map(Chunk::getPerceivedReplicationDegree));

        if (repDegreeIfItsInitiatedChunk.isPresent()){
            return repDegreeIfItsInitiatedChunk.get().get();
        }

        if (storedChunks.contains(chunk)){
            for (Chunk x : storedChunks){
                if (x.equals(chunk)) return x.getPerceivedReplicationDegree();
            }
        }
        if (allChunks.contains(chunk)){
            for (Chunk y : allChunks){
                if (y.equals(chunk)) return y.getPerceivedReplicationDegree();
            }
        }
        return -1;
    }

    /**
     * If the chunk is part of a file initiated by this peer, increments its perceived replication degree
     * Else tries to increment perceived replication degree of locally stored chunk. If its missing, add it to the set
     * @param chunk
     */
    public void incrementReplicationDegree(Chunk chunk) {
        if (findInitiatedFile(chunk.getFileID()).map(fileParser -> fileParser.incrementReplicationDegree(chunk)).isPresent())
            return;

        allChunks.add(new Chunk(chunk));
        for (Chunk x : allChunks){
            if (x.equals(chunk)) x.incrementPerceivedReplicationDegree();
        }
        for (Chunk y : storedChunks){
            if (y.equals(chunk)) y.incrementPerceivedReplicationDegree();
        }
    }

    /**
     * If the chunk is part of a file initiated by this peer, decrements its perceived replication degree
     * Else tries to decrement perceived replication degree of locally stored chunk.
     * @param chunk
     */
    public void decrementReplicationDegree(Chunk chunk) {
        if (findInitiatedFile(chunk.getFileID()).map(fileParser -> fileParser.decrementReplicationDegree(chunk)).isPresent())
            return;

        if (allChunks.contains(chunk)){
            for (Chunk x : allChunks){
                if (x.equals(chunk)) x.decrementPerceivedReplicationDegree();
            }
        }
        if (storedChunks.contains(chunk)){
            for (Chunk y : storedChunks){
                if (y.equals(chunk)) y.decrementPerceivedReplicationDegree();
            }
        }
    }

    /**
     * If this peer initiated chunk, returns its set of backed up peers, else returns Optional.empty
     * @param chunk
     * @return
     */
    public Optional<ConcurrentHashMap.KeySetView<Object, Boolean>> getBackedPeers(Chunk chunk){
        var result = chunksBackedPeers.get(chunk);
        return result != null ? Optional.of(result) : Optional.empty();
    }

    /**
     * If this peer initiated fileObject, returns its set of backed up peers, else returns Optional.empty
     * @param fileID
     * @return
     */
    public Set<Integer> getBackedPeersByFileObject(String fileID){
        Set<Integer> result = new HashSet<>();
        var actualFileOptional = findInitiatedFile(fileID);
        actualFileOptional.map(actualFile -> {
            for (Chunk chunk : actualFile.getChunks()){
                getBackedPeers(chunk).map(set -> {
                    for (Object peer : set){
                        result.add((Integer) peer);
                    }
                    return true;
                });
            }
            return true;
        });
        return result;
    }

    /**
     * Called when a STORED message is received, updates the initiators knowledge of what peers are backing up its chunk
     * @param chunk
     * @param newBackingPeer
     */
    public void updateChunksBackedPeers(Chunk chunk, int newBackingPeer){
        if (!isChunksInitiator(chunk)) return;

        var newPeerSet = ConcurrentHashMap.newKeySet();
        newPeerSet.add(newBackingPeer);

        var previousPeerSet = chunksBackedPeers.putIfAbsent(chunk, newPeerSet);
        if (previousPeerSet != null) previousPeerSet.add(newBackingPeer);
    }

    /**
     * If chunk was initiated by this peer, marks peerID as no longer backing it up
     * @param chunk
     * @param peerID
     */
    public void removeBackedPeerFromChunk(Chunk chunk, int peerID) {
        getBackedPeers(chunk).map(set -> set.remove(peerID));
    }

    /**
     * If file given by fileID was initiated by this peer, marks peerID as no longer backing up all of its chunks
     * @param fileID
     * @param peerID
     */
    public void removeBackedPeerFromAllChunks(String fileID, int peerID) {
        var actualFileObject = findInitiatedFile(fileID);
        if (actualFileObject.isEmpty()) return;
        for (Chunk chunk : actualFileObject.get().getChunks()){
            getBackedPeers(chunk).map(set -> set.remove(peerID));
        }
    }

    /**
     * Marks peerID as backing up chunk
     * @param chunk
     * @param peerID
     */
    public void addBackedPeer(Chunk chunk, int peerID) {
        getBackedPeers(chunk).map(set -> set.add(peerID));
    }

    /**
     * Add file to set of initiator's backed up files
     * @param file
     */
    public void initiateBackup(FileObject file) {
        initiatedFiles.add(file);
    }

    /**
     * Adds stored chunk to peers knowledge, taking into account the chunks perceived replication degree if its already in the system
     * @param chunk
     */
    private boolean addStoredChunk(Chunk chunk){
        if(allChunks.contains(chunk)){
            for (Chunk c : allChunks) {
                if (c.equals(chunk)) {
                    chunk.setPerceivedReplicationDegree(c.getPerceivedReplicationDegree());
                }
            }
        }
        return storedChunks.add(chunk);
    }

    /**
     * If the file corresponding to the given ID was backed up by this peer, return the FileParser object
     * @param fileID
     * @return If found, the corresponding FileParser object, otherwise Optional.empty
     */
    public Optional<FileObject> findInitiatedFile(String fileID){
        for (FileObject f : initiatedFiles) {
            if (f.getFileID().equals(fileID)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    /**
     * If the given chunk is either backed up by this peer or its file was initiated by this peer, returns it
     * @param chunk
     * @return If found, the corresponding Chunk object, otherwise Optional.empty
     */
    public Optional<Chunk> findChunk(Chunk chunk){
        var chunkIfItsInitiatedChunk = findInitiatedFile(chunk.getFileID()).map(fileObject -> fileObject.findChunk(chunk));
        if (chunkIfItsInitiatedChunk.isPresent())
            return chunkIfItsInitiatedChunk.get();

        for (Chunk c : storedChunks){
            if (c.equals(chunk)) return Optional.of(c);
        }
        return Optional.empty();
    }

    /**
     * Gets all backed up chunks belonging to give fileID
     * @param fileID
     * @return
     */
    public ArrayList<Chunk> findChunkByFileID(String fileID){
        ArrayList<Chunk> result = new ArrayList<>();
        for (Chunk c : storedChunks){
            if (c.getFileID().equals(fileID)) result.add(c);
        }
        return result;
    }

    /**
     * Checks if this peer is a given chunk's initiator
     * @param chunk
     * @return true if chunk was initiated by this peer, otherwise false
     */
    public boolean isChunksInitiator(Chunk chunk){
        for (FileObject file : initiatedFiles){
            if (file.getChunks().contains(chunk)) return true;
        }
        return false;
    }

    /**
     * Checks if this peer is a given file's initiator
     * @param file
     * @return true if file was initiated by this peer, otherwise false
     */
    public boolean isFilesInitiator(FileObject file){
        return initiatedFiles.contains(file);
    }

    /**
     * Checks if a given chunk is backed up by this peer
     * @param chunk
     * @return the stored chunk object if it exists, otherwise Optional.empty
     */
    public Optional<Chunk> hasChunkBackedUp(Chunk chunk){
        for(Chunk c : storedChunks){
            if (c.equals(chunk)) return Optional.of(c);
        }
        return Optional.empty();
    }

    /**
     * Space used up by the service on this peer
     * @return
     */
    public long getCurrentlyBytesUsedSpace() {
        long currentSpace = 0; // in KBytes

        for (Chunk c : storedChunks) {
            // chunk content length is in bytes.  B/1000 = KB
            currentSpace += (c.getContent().length);
        }

        return currentSpace;
    }

    /**
     * Called by peers with delete enhancement, will store peerID that failed to delete filepath
     * @param peerID
     * @param filepath
     */
    public void addPeerWithDeadChunks(int peerID, String filepath){
        if (!isFilesInitiator(new FileObject(filepath))) return;
        var newFilepathSet = ConcurrentHashMap.newKeySet();
        newFilepathSet.add(filepath);

        Set<Object> previousFilepathSet = peersWithDeadChunks.putIfAbsent(peerID, newFilepathSet);
        if (previousFilepathSet != null) previousFilepathSet.add(filepath);
    }

    public void setMaximumSpaceAvailable(long maximumSpaceAvailable) {
        this.maximumSpaceAvailable = maximumSpaceAvailable;
    }

    public long getMaximumSpaceAvailable() {
        return maximumSpaceAvailable;
    }

    public void removeInitiatedFile(FileObject fileObject) {
        initiatedFiles.remove(fileObject);
    }

    public void removeChunkFromAllChunkFiles(Chunk chunk) {
        allChunks.remove(chunk);
    }

    public void removeChunkFromStoredChunkFiles(Chunk chunk) {
        storedChunks.remove(chunk);
        deleteFileViaName(chunk.getChunkID());
    }

    public void deleteFileViaName(String filepath) {
        String newPath = chunksDir + "/" + filepath;
        File file = new File(newPath);

        String fileName = "Chunk file nr. " + filepath.substring(filepath.length() - 1);
        if (file.delete()) System.out.println(fileName + " deleted with success");
        else System.out.println(fileName + " not deleted");
    }

    public static void saveToDisk(){
        try{
            FileOutputStream fs = new FileOutputStream(Peer.serviceDirectory + "/" + "State");
            ObjectOutputStream os = new ObjectOutputStream(fs);
            os.writeObject(instance);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static FileStorage loadFromDisk() throws IOException {
        try{
            FileInputStream fs = new FileInputStream(Peer.serviceDirectory + "/" + "State");
            ObjectInputStream os = new ObjectInputStream(fs);
            FileStorage.instance = (FileStorage) os.readObject();
            return FileStorage.instance;
        } catch (FileNotFoundException e){
            System.out.println("File Storage not found ; Creating new one");
            return new FileStorage();
        }
        catch (IOException | ClassNotFoundException e){
            e.printStackTrace();
            return new FileStorage();
        }
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();

        var usedCapacity = getCurrentlyBytesUsedSpace();

        result.append("Capacity: ");
        if (usedCapacity < 1000) result.append(getCurrentlyBytesUsedSpace()).append("B / ");
        else                     result.append(getCurrentlyBytesUsedSpace() / 1000.0).append("KB / ");
        result.append(maximumSpaceAvailable).append("KB\n");

        result.append("BACKED UP FILES: ");
        if (initiatedFiles.size() > 0){
            for (FileObject file : initiatedFiles){
                result.append("\n").append(file.toString());
            }
        }
        else result.append("None\n");

        result.append("STORED CHUNKS: ");
        if (storedChunks.size() > 0){
            for (Chunk chunk : storedChunks){
                result.append("\n").append(chunk.toString());
            }
        }
        else result.append("None\n");

        return result.toString();
    }
}