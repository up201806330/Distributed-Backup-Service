import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashSet;

public class FileParser implements Serializable{

    private final int MAX_CHUNK_SIZE = 64000;

    private File file;
    private String filePath;
    private String fileID;
    private int replicationDegree;
    private LinkedHashSet<Chunk> chunks;
    boolean hasExtraEmptyChunk;

    public FileParser() { }

    public FileParser(String filePath, int replicationDegree) {
        this.file = new File(filePath);
        this.filePath = filePath;
        this.fileID = getFileIdHashed();
        this.replicationDegree = replicationDegree;
        this.chunks = parseChunks();
        this.hasExtraEmptyChunk = checkForEmptyEndingChunk();
    }

    public FileParser(String filepath) {
        this.filePath = filepath;
        this.file = new File(filepath);
        this.fileID = getFileIdHashed();

        this.replicationDegree = 0;
        this.chunks = null;
    }

    public static FileParser fromFileID(String fileID){
        var result = new FileParser();
        result.fileID = fileID;
        return result;
    }

    private LinkedHashSet<Chunk> parseChunks() {
        byte[] chunkBuffer = new byte[MAX_CHUNK_SIZE];
        int currentChunkNumber = 0;

        LinkedHashSet<Chunk> allChunks = new LinkedHashSet<>();

        try {
            FileInputStream fis = new FileInputStream(file);

            int size;
            while( (size = fis.read(chunkBuffer)) > 0) {
                Chunk createdChunk = new Chunk(fileID, ++currentChunkNumber, replicationDegree, Arrays.copyOf(chunkBuffer, size));
                allChunks.add(createdChunk);

                chunkBuffer = new byte[MAX_CHUNK_SIZE];
            }

            if (this.hasExtraEmptyChunk) {
                Chunk chunk = new Chunk(fileID, ++currentChunkNumber, replicationDegree);
                this.chunks.add(chunk);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        return allChunks;
    }

    private String getFileIdHashed() {

        // using file name, date modified and owner as suggested in handout
        String idToHash = file.getName() + file.lastModified() + file.getParent();

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(idToHash.getBytes("UTF-8"));
            StringBuffer hexString = new StringBuffer();

            for (byte aHash : hash) {
                String hex = Integer.toHexString(0xff & aHash);
                if (hex.length() == 1)
                    hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private boolean checkForEmptyEndingChunk() {
        return ((this.file.length() % MAX_CHUNK_SIZE) == 0);
    }

    public File getFile() {
        return file;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getFileID() {
        return fileID;
    }

    public int getReplicationDegree() {
        return replicationDegree;
    }

    public LinkedHashSet<Chunk> getChunks() {
        return chunks;
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder(
                "Pathname  : " + filePath + "\n" +
                "File ID   : " + fileID + "\n" +
                "Rep degree: " + replicationDegree + "\n" +
                "Chunks    : " + "\n");
        for (Chunk chunk : chunks){
            result.append(chunk.toSimpleString()).append("\n");
        }

        return result.toString();
    }

    @Override
    public int hashCode() {
        return fileID.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;

        FileParser other = (FileParser) obj;
        return fileID.equals(other.fileID);
    }
}
