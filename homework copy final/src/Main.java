import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Server.java
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Main {
    public static void main(String[] args) {
        try {
            Server server = new Server();
            server.processCommands(); // This will now wait for the "server" command
        } catch (Exception e) {
            System.err.println("Failed to start server: " + e.getMessage());
        }
    }
}