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
    private static final int HEADER_SIZE = 8;
    private static final int ENTRY_SIZE = 8;
    private Map<Integer, Integer> originalCosts; // Add this as a class field

    public Server() {
        this.routingTable = new ConcurrentHashMap<>();
        this.neighbors = new ConcurrentHashMap<>();
        this.connections = new ConcurrentHashMap<>();
        this.lastUpdateTime = new ConcurrentHashMap<>();
        this.originalCosts = new ConcurrentHashMap<>(); // Initialize it
        this.packetCount = 0;
        this.running = false;
    }

    private void loadTopology(String filename) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            numServers = Integer.parseInt(reader.readLine().trim());
            int numNeighbors = Integer.parseInt(reader.readLine().trim());

            String[] serverInfo = reader.readLine().trim().split("\\s+");
            this.serverId = Integer.parseInt(serverInfo[0]);
            this.serverIp = serverInfo[1];
            this.serverPort = Integer.parseInt(serverInfo[2]);

            // Add self-route with cost 0
            routingTable.put(serverId, new RoutingEntry(serverId, serverId, 0));

            Map<Integer, ServerInfo> allServers = new HashMap<>();
            allServers.put(serverId, new ServerInfo(serverId, serverIp, serverPort));

            for (int i = 0; i < numNeighbors; i++) {
                String[] otherServerInfo = reader.readLine().trim().split("\\s+");
                int id = Integer.parseInt(otherServerInfo[0]);
                String ip = otherServerInfo[1];
                int port = Integer.parseInt(otherServerInfo[2]);
                allServers.put(id, new ServerInfo(id, ip, port));
            }

            for (Map.Entry<Integer, ServerInfo> entry : allServers.entrySet()) {
                int id = entry.getKey();
                if (id != serverId) {
                    routingTable.put(id, new RoutingEntry(id, -1, Integer.MAX_VALUE));
                }
            }

            for (int i = 0; i < numNeighbors; i++) {
                String[] linkInfo = reader.readLine().trim().split("\\s+");
                int server1 = Integer.parseInt(linkInfo[0]);
                int server2 = Integer.parseInt(linkInfo[1]);
                int cost = Integer.parseInt(linkInfo[2]);

                if (server1 == serverId) {
                    neighbors.put(server2, allServers.get(server2));
                    routingTable.put(server2, new RoutingEntry(server2, server2, cost));
                    originalCosts.put(server2, cost); // Store original cost

                } else if (server2 == serverId) {
                    neighbors.put(server1, allServers.get(server1));
                    routingTable.put(server1, new RoutingEntry(server1, server1, cost));
                    originalCosts.put(server1, cost); // Store original cost
                }
            }
        }
    }

    private void start() {
        if (!running) return;
        new Thread(this::acceptConnections).start();
        new Thread(this::periodicUpdate).start();
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
            if (neighborId != serverId) {
                try {
                    Socket socket = new Socket(entry.getValue().getIp(), entry.getValue().getPort());
                    ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                    ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

                    out.writeObject(serverId);
                    out.flush();

                    Connection conn = new Connection(socket, in, out, neighborId);
                    connections.put(neighborId, conn);

                    // Restore original cost from saved costs
                    if (originalCosts.containsKey(neighborId)) {
                        int originalCost = originalCosts.get(neighborId);
                        routingTable.put(neighborId, new RoutingEntry(neighborId, neighborId, originalCost));
                        // setn immediate updatr
                        sendRoutingUpdate();
                    }

                    new Thread(() -> receiveUpdates(conn)).start();

                    // Send immediate update
                    sendRoutingUpdate();
                } catch (IOException e) {
                    System.err.println("Failed to connect to neighbor " + neighborId);
                }
            }
        }
    }

    public void processCommands() {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            String command = scanner.nextLine().trim();
            String[] parts = command.split("\\s+");

            try {
                switch (parts[0].toLowerCase()) {
                    case "server":
                        handleServerCommand(parts);
                        break;
                    case "update":
                        if (!running) {
                            System.out.println(command + " ERROR: Server not running");
                            continue;
                        }
                        handleUpdate(parts);
                        break;
                    case "step":
                        if (!running) {
                            System.out.println(command + " ERROR: Server not running");
                            continue;
                        }
                        handleStep();
                        break;
                    case "packets":
                        if (!running) {
                            System.out.println(command + " ERROR: Server not running");
                            continue;
                        }
                        handlePackets();
                        break;
                    case "display":

//                        if (!running) {
//                            System.out.println(command + " ERROR: Server not running");
//                            continue;
//                        }

                        handleDisplay();
                        break;
                    case "disable":
                        if (!running) {
                            System.out.println(command + " ERROR: Server not running");
                            continue;
                        }
                        handleDisable(parts);
                        break;
                    case "crash":
                        if (!running) {
                            System.out.println(command + " ERROR: Server not running");
                            continue;
                        }
                        handleCrash();
                        return;
                    default:
                        System.out.println(command + " ERROR: Invalid command");
                }
            } catch (Exception e) {
                System.out.println(command + " ERROR: " + e.getMessage());
            }
        }
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
            this.running = true;
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
                checkTimeouts();
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
        while (running) {
            try {
                Object received = conn.getInputStream().readObject();
                if (received instanceof RoutingUpdate) {


                    RoutingUpdate update = (RoutingUpdate) received;
                    packetCount++;
                    lastUpdateTime.put(conn.getNeighborId(), System.currentTimeMillis());
                    int neighborId = conn.getNeighborId();
                    if (originalCosts.containsKey(neighborId)) {
                        int originalCost = originalCosts.get(neighborId);
                        routingTable.put(neighborId, new RoutingEntry(neighborId, neighborId, originalCost));
                    }
                    //
//                    conn.updateLastReceivedRoutes(update.getRoutes());
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
        Connection conn = connections.remove(neighborId);
        if (conn != null) {
            try {
                conn.getSocket().close();
            } catch (IOException ignored) {}
        }
        // Keep the original cost in the originalCosts map but mark as unreachable in routing table
        routingTable.put(neighborId, new RoutingEntry(neighborId, -1, Integer.MAX_VALUE));

        boolean changed = false;
        for (Map.Entry<Integer, RoutingEntry> entry : routingTable.entrySet()) {
            if (entry.getValue().getNextHop() == neighborId) {
                routingTable.put(entry.getKey(), new RoutingEntry(entry.getKey(), -1, Integer.MAX_VALUE));
                changed = true;
            }
        }

        lastUpdateTime.remove(neighborId);

        if (changed) {
            sendRoutingUpdate();
        }
    }


    // updating routing table
    private void updateRoutingTable(RoutingUpdate update) {
        int sourceId = update.getServerId();
        Map<Integer, RoutingEntry> receivedRoutes = update.getRoutes();
        boolean changed = false;

        // If this is from a neighbor, restore the direct connection cost
        if (neighbors.containsKey(sourceId)) {
            int directCost = routingTable.get(sourceId).getCost();
            if (directCost == Integer.MAX_VALUE) {
                // This is a reconnection - restore original cost from topology
                RoutingEntry originalEntry = routingTable.get(sourceId);
                routingTable.put(sourceId,
                        new RoutingEntry(sourceId, sourceId, originalEntry.getCost()));
                changed = true;
            }
        }

        int linkCostToSource = routingTable.get(sourceId).getCost();

        for (Map.Entry<Integer, RoutingEntry> entry : receivedRoutes.entrySet()) {
            int destId = entry.getKey();
            int receivedCost = entry.getValue().getCost();

            if (destId == serverId) continue;

            if (receivedCost == Integer.MAX_VALUE) {
                if (routingTable.containsKey(destId) &&
                        routingTable.get(destId).getNextHop() == sourceId) {
                    routingTable.put(destId,
                            new RoutingEntry(destId, -1, Integer.MAX_VALUE));
                    changed = true;
                }
                continue;
            }

            long totalCost = (long) receivedCost + (long) linkCostToSource;
            int newCost = totalCost > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalCost;

            RoutingEntry currentEntry = routingTable.get(destId);
            if (currentEntry == null ||
                    newCost < currentEntry.getCost() ||
                    (currentEntry.getNextHop() == sourceId && newCost != currentEntry.getCost())) {
                routingTable.put(destId, new RoutingEntry(destId, sourceId, newCost));
                changed = true;
            }
        }

        if (changed) {
            sendRoutingUpdate();
        }
    }


    private void checkTimeouts() {
        long currentTime = System.currentTimeMillis();
        Set<Integer> failedNeighbors = new HashSet<>();

        for (Map.Entry<Integer, Connection> entry : connections.entrySet()) {
            int neighborId = entry.getKey();
            Long lastUpdate = lastUpdateTime.get(neighborId);

            if (lastUpdate != null &&
                    (currentTime - lastUpdate) > (TIMEOUT_MULTIPLIER * updateInterval)) {
                failedNeighbors.add(neighborId);
            }
        }

        for (int neighborId : failedNeighbors) {
            handleConnectionFailure(neighborId);
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
            // Optionally show unreachable nodes with "inf" cost
            if (route.getCost() == Integer.MAX_VALUE) {
                System.out.printf("%d %d inf%n",
                        route.getDestination(),
                        route.getNextHop());
            } else {
                System.out.printf("%d %d %d%n",
                        route.getDestination(),
                        route.getNextHop(),
                        route.getCost());
            }
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