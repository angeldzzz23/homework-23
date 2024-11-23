import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

// this handles the connection
class Connection {
    private Socket socket;
    private ObjectInputStream in;
    private ObjectOutputStream out;
    private int neighborId;
    private Map<Integer, RoutingEntry> lastReceivedRoutes;

    public Connection(Socket socket, ObjectInputStream in, ObjectOutputStream out, int neighborId) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.neighborId = neighborId;
        this.lastReceivedRoutes = new HashMap<>();
    }

    public Socket getSocket() { return socket; }
    public ObjectInputStream getInputStream() { return in; }
    public ObjectOutputStream getOutputStream() { return out; }
    public int getNeighborId() { return neighborId; }

    public void updateLastReceivedRoutes(Map<Integer, RoutingEntry> routes) {
        this.lastReceivedRoutes = new HashMap<>(routes);
    }

    public Map<Integer, RoutingEntry> getLastReceivedRoutes() {
        return lastReceivedRoutes;
    }
}