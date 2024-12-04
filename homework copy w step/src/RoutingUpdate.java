import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

//
class RoutingUpdate implements Serializable {
    private static final long serialVersionUID = 1L;
    private final int serverId;
    private final Map<Integer, RoutingEntry> routes;

    public RoutingUpdate(int serverId, Map<Integer, RoutingEntry> routes) {
        this.serverId = serverId;
        this.routes = new HashMap<>(routes);
    }

    public int getServerId() { return serverId; }
    public Map<Integer, RoutingEntry> getRoutes() { return routes; }
}
