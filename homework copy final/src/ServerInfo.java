import java.io.Serializable;

class ServerInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    private final int id;
    private final String ip;
    private final int port;

    public ServerInfo(int id, String ip, int port) {
        this.id = id;
        this.ip = ip;
        this.port = port;
    }

    public int getId() {
        return id;
    }

    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }
}
