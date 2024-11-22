import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

// this handles the connection
class Connection {
    private final Socket socket;
    private final ObjectInputStream inputStream;
    private final ObjectOutputStream outputStream;
    private final int neighborId;

    public Connection(Socket socket, ObjectInputStream in, ObjectOutputStream out, int neighborId) {
        this.socket = socket;
        this.inputStream = in;
        this.outputStream = out;
        this.neighborId = neighborId;
    }

    public Socket getSocket() { return socket; }
    public ObjectInputStream getInputStream() { return inputStream; }
    public ObjectOutputStream getOutputStream() { return outputStream; }
    public int getNeighborId() { return neighborId; }
}