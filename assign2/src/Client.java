// ----- Client.java -----
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.*;

public class Client {
    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 8888;

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private BufferedReader console;
    private String username;
    private String currentRoom;
    private boolean running = true;

    private final Set<String> joinedRooms = new HashSet<>();
    private final Set<String> availableRooms = new HashSet<>();
    private final Set<String> aiRooms = new HashSet<>();
    private final ReadWriteLock roomsLock = new ReentrantReadWriteLock();

    public static void main(String[] args) {
        new Client().start();
    }

    public void start() {
        try {
            connect();
            authenticate();
            Thread.startVirtualThread(this::receiveMessages);
            handleInput();
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    private void connect() throws IOException {
        socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new PrintWriter(socket.getOutputStream(), true);
        console = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Connected to server");
    }

    private void authenticate() throws IOException {
        System.out.println(in.readLine());  // AUTH:Welcome...
        while (true) {
            System.out.print("Username: ");
            String user = console.readLine();
            System.out.print("Password: ");
            String pass = console.readLine();
            out.println("LOGIN:" + user + ":" + pass);

            String response = in.readLine();
            if (response != null && response.startsWith("AUTH_OK:")) {
                username = response.substring("AUTH_OK:".length());
                System.out.println("Welcome, " + username + "!");
                showHelp();
                break;
            } else {
                String reason = (response != null && response.startsWith("AUTH_FAIL:"))
                        ? response.substring("AUTH_FAIL:".length())
                        : "Invalid response from server";
                System.out.println("Auth failed: " + reason + ". Try again.\n");
            }
        }
    }

    private void receiveMessages() {
        try {
            String msg;
            while (running && (msg = in.readLine()) != null) {
                if (msg.startsWith("MESSAGE:")) {
                    String[] p = msg.substring(8).split(":", 3);
                    printMessage(p[0], p[1], p[2]);
                } else if (msg.startsWith("SYSTEM:")) {
                    String[] p = msg.substring(7).split(":", 2);
                    printSystem(p[0], p[1]);
                } else if (msg.startsWith("ROOMS:")) {
                    updateAvailableRooms(msg.substring(6));
                } else if (msg.startsWith("JOINED:")) {
                    String room = msg.substring(7);
                    addJoinedRoom(room);
                    currentRoom = room;
                    System.out.println("Joined: " + room);
                    if (aiRooms.contains(room)) System.out.println("[INFO] AI room: bot will respond here.");
                } else if (msg.startsWith("LEFT:")) {
                    String room = msg.substring(5);
                    removeJoinedRoom(room);
                    System.out.println("Left: " + room);
                } else {
                    System.out.println(msg);
                }
            }
        } catch (IOException e) {
            if (running) System.err.println("Connection lost");
        }
    }

    private void handleInput() throws IOException {
        String line;
        while (running && (line = console.readLine()) != null) {
            if (line.startsWith("/")) {
                handleCommand(line);
            } else if (currentRoom != null) {
                out.println("MESSAGE:" + currentRoom + ":" + line);
                if (aiRooms.contains(currentRoom)) System.out.println("Waiting for bot...");
            } else {
                System.out.println("Join a room first (/join <room>)");
            }
        }
    }

    private void handleCommand(String input) {
        String[] parts = input.substring(1).split(" ", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";
        switch (cmd) {
            case "join":
                if (!arg.isBlank()) out.println("JOIN:" + arg);
                else System.out.println("Usage: /join <room> or /join AI:<name>:<prompt>");
                break;
            case "leave":
                if (currentRoom != null) out.println("LEAVE:" + currentRoom);
                else System.out.println("Not in any room");
                break;
            case "rooms":
                showRooms();
                break;
            case "logout":
                running = false;
                out.println("LOGOUT");
                break;
            case "help":
                showHelp();
                break;
            default:
                System.out.println("Unknown command. Type /help");
        }
    }

    private void printMessage(String room, String sender, String content) {
        String prefix = sender.equals("Bot") ? "🤖 " : "";
        if (room.equals(currentRoom)) {
            System.out.println(prefix + sender + ": " + content);
        } else {
            System.out.println("[" + room + "] " + prefix + sender + ": " + content);
        }
    }

    private void printSystem(String room, String msg) {
        System.out.println("[" + room + "] System: " + msg);
    }

    private void updateAvailableRooms(String list) {
        roomsLock.writeLock().lock();
        try {
            availableRooms.clear();
            aiRooms.clear();
            for (String r : list.split(",")) {
                if (r.isBlank()) continue;
                availableRooms.add(r);
                if (r.startsWith("AI")) aiRooms.add(r);
            }
            System.out.println("Available rooms: " + availableRooms);
        } finally {
            roomsLock.writeLock().unlock();
        }
    }

    private void showRooms() {
        roomsLock.readLock().lock();
        try {
            System.out.println("Current: " + (currentRoom != null ? currentRoom : "(none)"));
            System.out.println("Joined: " + joinedRooms);
            System.out.println("Available: " + availableRooms);
        } finally {
            roomsLock.readLock().unlock();
        }
    }

    private void addJoinedRoom(String r) {
        roomsLock.writeLock().lock();
        try { joinedRooms.add(r); }
        finally { roomsLock.writeLock().unlock(); }
    }

    private void removeJoinedRoom(String r) {
        roomsLock.writeLock().lock();
        try {
            joinedRooms.remove(r);
            if (r.equals(currentRoom)) currentRoom = null;
        } finally {
            roomsLock.writeLock().unlock();
        }
    }

    private void showHelp() {
        System.out.println("Commands:");
        System.out.println("/join <room> - join or create regular room");
        System.out.println("/join AI:<name>:<prompt> - join or create AI room");
        System.out.println("/leave - leave current room");
        System.out.println("/rooms - show current/joined/available rooms");
        System.out.println("/logout - exit");
        System.out.println("/help - this menu");
    }

    private void cleanup() {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }
}
