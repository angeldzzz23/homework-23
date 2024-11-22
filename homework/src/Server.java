import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// the server info
public class Server {
    private int serverId;
    private String serverIp;
    private int serverPort;
    private Map<Integer, RoutingEntry> routingTable;
    private Map<Integer, ServerInfo> neighbors;
    private Map<Integer, Connection> connections;
    private int numServers;
    private int updateInterval;
    private int packetCount;
    private ServerSocket serverSocket;
    private volatile boolean running;
    private Map<Integer, Long> lastUpdateTime;
    private static final int TIMEOUT_MULTIPLIER = 3;

    public Server() {
        this.routingTable = new ConcurrentHashMap<>();
        this.neighbors = new ConcurrentHashMap<>();
        this.connections = new ConcurrentHashMap<>();
        this.lastUpdateTime = new ConcurrentHashMap<>();
        this.packetCount = 0;
        this.running = true;
    }


    private void loadTopology(String filename) throws IOException {

        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            numServers = Integer.parseInt(reader.readLine().trim()); // Number of servers
            int numNeighbors = Integer.parseInt(reader.readLine().trim()); // Number of neighbors

            // Read the current server's info
            String[] serverInfo = reader.readLine().trim().split("\\s+");
            this.serverId = Integer.parseInt(serverInfo[0]);
            this.serverIp = serverInfo[1];
            this.serverPort = Integer.parseInt(serverInfo[2]);

            // Parse all server information
            Map<Integer, ServerInfo> allServers = new HashMap<>();
            allServers.put(serverId, new ServerInfo(serverId, serverIp, serverPort)); // Add current server
            for (int i = 0; i < numNeighbors; i++) { // Read the remaining servers
                String[] otherServerInfo = reader.readLine().trim().split("\\s+");
                int id = Integer.parseInt(otherServerInfo[0]);
                String ip = otherServerInfo[1];
                int port = Integer.parseInt(otherServerInfo[2]);
                allServers.put(id, new ServerInfo(id, ip, port));
            }

            // Initialize the routing table
            // TODO:  This is wrong
            // Initialize the routing table with default values for all servers except the current one
            for (Map.Entry<Integer, ServerInfo> entry : allServers.entrySet()) {
                int id = entry.getKey();
                if (id != serverId) { // Skip the current server itself
                    routingTable.put(id, new RoutingEntry(id, -1, Integer.MAX_VALUE)); // Default to unreachable
                }
            }

            // Parse neighbor information
            for (int i = 0; i < numNeighbors; i++) {
                String[] linkInfo = reader.readLine().trim().split("\\s+");
                int server1 = Integer.parseInt(linkInfo[0]);
                int server2 = Integer.parseInt(linkInfo[1]);
                int cost = Integer.parseInt(linkInfo[2]);

                if (server1 == serverId) {
                    neighbors.put(server2, allServers.get(server2));
                    routingTable.put(server2, new RoutingEntry(server2, server2, cost));
                } else if (server2 == serverId) {
                    neighbors.put(server1, allServers.get(server1));
                    routingTable.put(server1, new RoutingEntry(server1, server1, cost));
                }
            }

        }

    }

    private void start() {
        new Thread(this::acceptConnections).start();
        new Thread(this::periodicUpdate).start();
        new Thread(this::checkTimeouts).start();
        connectToNeighbors();
    }

    private void acceptConnections() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleNewConnection(clientSocket)).start();
            } catch (IOException e) {
                if (running) {
                    System.err.println("Error accepting connection: " + e.getMessage());
                }
            }
        }
    }

    private void handleNewConnection(Socket socket) {
        try {
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

            int neighborId = (Integer) in.readObject();

            if (neighbors.containsKey(neighborId)) {
                Connection conn = new Connection(socket, in, out, neighborId);
                connections.put(neighborId, conn);
                new Thread(() -> receiveUpdates(conn)).start();
            } else {
                socket.close();
            }
        } catch (Exception e) {
            System.err.println("Error handling new connection: " + e.getMessage());
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private void connectToNeighbors() {
        for (Map.Entry<Integer, ServerInfo> entry : neighbors.entrySet()) {
            int neighborId = entry.getKey();
            if (neighborId > serverId) {
                try {
                    Socket socket = new Socket(entry.getValue().getIp(), entry.getValue().getPort());
                    ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                    ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

                    out.writeObject(serverId);
                    out.flush();

                    Connection conn = new Connection(socket, in, out, neighborId);
                    connections.put(neighborId, conn);
                    new Thread(() -> receiveUpdates(conn)).start();
                } catch (IOException e) {
                    System.err.println("Failed to connect to neighbor " + neighborId);
                }
            }
        }
    }

    public void processCommands() {
        Scanner scanner = new Scanner(System.in);
        while (running) {
            String command = scanner.nextLine().trim();
            String[] parts = command.split("\\s+");

            try {
                switch (parts[0].toLowerCase()) {
                    case "server":
                        handleServerCommand(parts);
                        break;
                    case "update":
                        handleUpdate(parts);
                        break;
                    case "step":
                        handleStep();
                        break;
                    case "packets":
                        handlePackets();
                        break;
                    case "display":
                        handleDisplay();
                        break;
                    case "disable":
                        handleDisable(parts);
                        break;
                    case "crash":
                        handleCrash();
                        break;
                    default:
                        System.out.println(command + " ERROR: Invalid command");
                }
            } catch (Exception e) {
                System.out.println(command + " ERROR: " + e.getMessage());
            }
        }
        scanner.close();
    }

    private void handleServerCommand(String[] parts) throws Exception {
        if (parts.length != 5 || !parts[1].equals("-t") || !parts[3].equals("-i")) {
            throw new IllegalArgumentException("Usage: server -t <topology-file-name> -i <routing-update-interval>");
        }

        String topologyFile = parts[2];
        int updateInterval = Integer.parseInt(parts[4]);

        try {
            loadTopology(topologyFile);
            this.serverSocket = new ServerSocket(serverPort);
            this.updateInterval = updateInterval * 1000;
            start();
            System.out.println("server -t " + topologyFile + " -i " + updateInterval + " SUCCESS");
        } catch (Exception e) {
            throw new Exception("Failed to initialize server: " + e.getMessage());
        }
    }

    private void periodicUpdate() {
        while (running) {
            try {
                Thread.sleep(updateInterval);
                sendRoutingUpdate();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void sendRoutingUpdate() {
        RoutingUpdate update = new RoutingUpdate(serverId, routingTable);
        for (Connection conn : connections.values()) {
            try {
                conn.getOutputStream().writeObject(update);
                conn.getOutputStream().flush();
            } catch (IOException e) {
                System.err.println("Failed to send update to server " + conn.getNeighborId());
                handleConnectionFailure(conn.getNeighborId());
            }
        }
    }

    private void receiveUpdates(Connection conn) {
        System.out.println(conn);
        while (running) {
            try {
                Object received = conn.getInputStream().readObject();
                if (received instanceof RoutingUpdate) {
                    RoutingUpdate update = (RoutingUpdate) received;
                    packetCount++;
                    System.out.println("RECEIVED A MESSAGE FROM SERVER " + update.getServerId());
                    lastUpdateTime.put(conn.getNeighborId(), System.currentTimeMillis());
                    updateRoutingTable(update);
                }
            } catch (Exception e) {
                if (running) {
                    System.err.println("Error receiving update from server " + conn.getNeighborId());
                    handleConnectionFailure(conn.getNeighborId());
                }
                break;
            }
        }
    }

    //handling connection failure
    private void handleConnectionFailure(int neighborId) {
        // Remove connection
        Connection conn = connections.remove(neighborId);
        if (conn != null) {
            try {
                conn.getSocket().close();
            } catch (IOException ignored) {}
        }

        // Mark direct route to failed neighbor as infinite
        routingTable.put(neighborId, new RoutingEntry(neighborId, neighborId, Integer.MAX_VALUE));

        // Find and invalidate all routes that went through the failed neighbor
        boolean changed = false;
        for (Map.Entry<Integer, RoutingEntry> entry : routingTable.entrySet()) {
            if (entry.getValue().getNextHop() == neighborId) {
                routingTable.put(entry.getKey(),
                        new RoutingEntry(entry.getKey(), -1, Integer.MAX_VALUE));
                changed = true;
            }
        }

        // Clean up monitoring state
        lastUpdateTime.remove(neighborId);

        // Propagate changes if any routes were invalidated
        if (changed) {
            sendRoutingUpdate();
        }
    }

    private void updateRoutingTable(RoutingUpdate update) {
        int sourceId = update.getServerId();
        Map<Integer, RoutingEntry> receivedRoutes = update.getRoutes();
        boolean changed = false;

        // Get the current link cost to the source
        int linkCostToSource = routingTable.get(sourceId).getCost();

        // First, identify routes that need to be invalidated
        Set<Integer> destinations = new HashSet<>();
        for (Map.Entry<Integer, RoutingEntry> entry : routingTable.entrySet()) {
            if (entry.getValue().getNextHop() == sourceId) {
                destinations.add(entry.getKey());
            }
        }

        // Process received routes
        for (Map.Entry<Integer, RoutingEntry> entry : receivedRoutes.entrySet()) {
            int destId = entry.getKey();
            int receivedCost = entry.getValue().getCost();

            if (destId == serverId) continue; // Skip routes to self

            // Calculate new cost including the link cost to source
            int newCost;
            if (receivedCost == Integer.MAX_VALUE || linkCostToSource == Integer.MAX_VALUE) {
                newCost = Integer.MAX_VALUE;
            } else {
                // Check for integer overflow
                long totalCost = (long) receivedCost + (long) linkCostToSource;
                newCost = totalCost > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalCost;
            }

            RoutingEntry currentEntry = routingTable.get(destId);
            if (currentEntry == null) {
                // New destination discovered
                routingTable.put(destId, new RoutingEntry(destId, sourceId, newCost));
                changed = true;
                continue;
            }

            // Remove from invalidation set if we received an update
            destinations.remove(destId);

            // Update route if:
            // 1. Current route goes through source (forced update)
            // 2. New route is better than current route
            // 3. Current route is through a failed link (cost = MAX_VALUE)
            if (currentEntry.getNextHop() == sourceId ||
                    newCost < currentEntry.getCost() ||
                    currentEntry.getCost() == Integer.MAX_VALUE) {

                routingTable.put(destId, new RoutingEntry(destId, sourceId, newCost));
                changed = true;
            }
        }

        // Invalidate routes that weren't included in the update but went through the source
        for (int destId : destinations) {
            routingTable.put(destId, new RoutingEntry(destId, -1, Integer.MAX_VALUE));
            changed = true;
        }

        // If routes changed, propagate updates to neighbors
        if (changed) {
            sendRoutingUpdate();
        }
    }




    private void checkTimeouts() {
        long currentTime = System.currentTimeMillis();
        Set<Integer> failedNeighbors = new HashSet<>();

        // Identify all neighbors that have timed out
        for (Map.Entry<Integer, Connection> entry : connections.entrySet()) {
            int neighborId = entry.getKey();
            Long lastUpdate = lastUpdateTime.get(neighborId);

            if (lastUpdate != null &&
                    (currentTime - lastUpdate) > (TIMEOUT_MULTIPLIER * updateInterval)) {
                failedNeighbors.add(neighborId);
            }
        }

        // Handle all failures at once to prevent multiple routing table updates
        if (!failedNeighbors.isEmpty()) {
            for (int neighborId : failedNeighbors) {
                handleConnectionFailure(neighborId);
            }
        }
    }

    private void handleUpdate(String[] parts) {
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid update command format");
        }

        int server1 = Integer.parseInt(parts[1]);
        int server2 = Integer.parseInt(parts[2]);
        int newCost = parts[3].equalsIgnoreCase("inf") ?
                Integer.MAX_VALUE : Integer.parseInt(parts[3]);

        if (server1 != serverId && server2 != serverId) {
            throw new IllegalArgumentException("At least one server must be the current server");
        }

        int neighborId = (server1 == serverId) ? server2 : server1;
        if (!neighbors.containsKey(neighborId)) {
            throw new IllegalArgumentException("Server is not a neighbor");
        }

        routingTable.put(neighborId, new RoutingEntry(neighborId, neighborId, newCost));
        System.out.println("update " + server1 + " " + server2 + " " + parts[3] + " SUCCESS");
    }

    private void handleStep() {
        sendRoutingUpdate();
        System.out.println("step SUCCESS");
    }

    private void handlePackets() {
        System.out.println("packets SUCCESS");
        System.out.println(packetCount);
        packetCount = 0;
    }

    private void handleDisplay() {
        System.out.println("display SUCCESS");
        List<Map.Entry<Integer, RoutingEntry>> sortedEntries =
                new ArrayList<>(routingTable.entrySet());
        sortedEntries.sort(Map.Entry.comparingByKey());

        for (Map.Entry<Integer, RoutingEntry> entry : sortedEntries) {
            RoutingEntry route = entry.getValue();
            System.out.printf("%d %d %d%n",
                    route.getDestination(),
                    route.getNextHop(),
                    route.getCost() == Integer.MAX_VALUE ? -1 : route.getCost());
        }
    }

    private void handleDisable(String[] parts) {
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid disable command format");
        }

        int serverId = Integer.parseInt(parts[1]);
        if (!neighbors.containsKey(serverId)) {
            throw new IllegalArgumentException("Server is not a neighbor");
        }

        handleConnectionFailure(serverId);
        System.out.println("disable " + serverId + " SUCCESS");
    }

    private void handleCrash() {
        running = false;
        for (Connection conn : connections.values()) {
            try {
                conn.getSocket().close();
            } catch (IOException ignored) {}
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
        System.out.println("crash SUCCESS");
        System.exit(0);
    }
}