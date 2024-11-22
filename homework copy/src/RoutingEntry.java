import java.io.Serializable;

class RoutingEntry implements Serializable {
    private static final long serialVersionUID = 1L;
    private final int destination;
    private final int nextHop;
    private final int cost;

    public RoutingEntry(int destination, int nextHop, int cost) {
        this.destination = destination;
        this.nextHop = nextHop;
        this.cost = cost;
    }

    public int getDestination() { return destination; }
    public int getNextHop() { return nextHop; }
    public int getCost() { return cost; }
}
