import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;
import java.util.Arrays;

public class Channel implements Runnable {
    enum ChannelType { MC, MDB, MDR };

    private InetAddress inetAddress;
    private final int port;
    private final ChannelType type;
    private final MulticastSocket socket;

    public Channel(String addressString, int port, ChannelType type) throws IOException {
        try {
            this.inetAddress = InetAddress.getByName(addressString);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        this.port = port;
        this.type = type;

        this.socket = new MulticastSocket(this.port);
        socket.joinGroup(this.inetAddress);
    }

    @Override
    public void run() {
        System.out.println("Started up channel " + type);

        byte[] buffer = new byte[80000]; // 80000 > 64000
        DatagramPacket receivedPacket = new DatagramPacket(buffer, buffer.length);
        while (true) {
            try {
                this.socket.receive(receivedPacket);

            } catch (IOException e) {
                e.printStackTrace();
            }

            processPacket(receivedPacket);
        }
    }

    private void processPacket(DatagramPacket receivedPacket) {
        int size = receivedPacket.getLength();
        byte[] data = Arrays.copyOf(receivedPacket.getData(), size);

        int i;
        for (i = 0; i < data.length; i++) {
            if (data[i] == 0xD && data[i + 1] == 0xA && data[i + 2] == 0xD && data[i + 3] == 0xA) break;
        }

        byte[] header = Arrays.copyOfRange(data, 0, i);
        String[] splitHeader = new String(header).trim().split(" ");

        // Peer ignores all messages that it doesnt understand
        if (!splitHeader[0].equals(Peer.protocolVersion)) return;

        String command = splitHeader[1];
        int senderID = Integer.parseInt(splitHeader[2]);
        String fileID = (splitHeader.length >= 4 ? splitHeader[3] : "").toUpperCase();
        int chunkNr = splitHeader.length >= 5 ? Integer.parseInt(splitHeader[4]) : 0;
        int desiredRepDegree = splitHeader.length == 6 ? Integer.parseInt(splitHeader[5]) : -1;

        Chunk newChunk = new Chunk(fileID, chunkNr, desiredRepDegree);
        switch (command) {
            case "PUTCHUNK":
                byte[] body = Arrays.copyOfRange(data, i + 4, data.length);
                newChunk.setContent(body);
                Peer.getExec().execute(() -> Backup.processPUTCHUNK(newChunk, splitHeader));
                break;
            case "STORED":
                Peer.getExec().execute(() ->Backup.processSTORED(newChunk, splitHeader));
                break;
            case "DELETE":
                Peer.getExec().execute(() ->Delete.processDELETE(fileID));
                break;
            case "GETCHUNK":
                Peer.getExec().execute(() ->Restore.processGETCHUNK(splitHeader));
                break;
            case "CHUNK":
                byte[] content = Arrays.copyOfRange(data, i + 4, data.length);
                newChunk.setContent(content);
                Peer.getExec().execute(() ->Restore.processCHUNK(newChunk, splitHeader));
                break;
            case "REMOVED":
                Peer.getExec().execute(() ->Reclaim.processREMOVED(splitHeader));
                break;
            case "DELETED": // From delete enhancement
                Peer.getExec().execute(() -> Delete.processDELETED(senderID, fileID));
                break;
            case "ONLINE": // From delete enhancement
                Peer.getExec().execute(() -> Peer.processONLINE(senderID));
                break;
            default:
                break;
        }
    }

    public synchronized void sendMessage(byte[] buf) {
        try {
            socket.send(new DatagramPacket(buf, buf.length, this.inetAddress, this.port));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
