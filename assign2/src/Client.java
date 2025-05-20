// ----- Client.java -----
import javax.sound.midi.Soundbank;
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

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";

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
        System.out.println("Connected to server.\n");
    }

    private void authenticate() throws IOException {
        System.out.println(in.readLine());  // AUTH:Welcome...
        while (true) {
            System.out.print("\nEnter your username: ");
            String user = console.readLine();
            System.out.print("Enter your password: ");
            String pass = console.readLine();
            out.println("LOGIN:" + user + ":" + pass);

            String response = in.readLine();
            if (response != null) {
                if (response.startsWith("AUTH_NEW:")) {
                    username = user;
                    //System.out.println("\n" + response.substring("AUTH_NEW:".length()));
                    //System.out.println(response.substring("AUTH_NEW:".length()));  // Welcome, new user ...
                    System.out.println("\n\uD83C\uDF89 Welcome, new user " + username + "!");
                    showHelp();
                    break;
                } else if (response.startsWith("AUTH_OK:")) {
                    username = user;
                    //System.out.println(response.substring("AUTH_OK:".length()));// Welcome back, ...
                    System.out.println("\n✅ Logged in successfully!");
                    System.out.println("Welcome back " + user + "!");
                    showHelp();
                    break;
                } else if (response.startsWith("AUTH_FAIL:")) {
                    String reason = response.substring("AUTH_FAIL:".length());
                    System.out.println("\n⚠️ Error login in.");
                    System.out.println(reason + ". " + "Try again.");
                } else {
                    System.out.println("Invalid response from server: " + response);
                }
            } else {
                System.out.println("Server disconnected.");
                break;
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
                    printSystemMessage(p[0], p[1]);
                } else if (msg.startsWith("ROOMS:")) {
                    updateAvailableRooms(msg.substring(6));
                } else if (msg.startsWith("JOINED:")) {
                    String room = msg.substring(7);
                    addJoinedRoom(room);
                    currentRoom = room;
                    String joinedRoom = "Joined: ";
                    System.out.println(printBold(joinedRoom) + room);
                    if (aiRooms.contains(room)) {
                        String aiRoom = "[INFO] AI room: ";
                        System.out.println(printBold(aiRoom) + "bot will respond here.");
                    }
                } else if (msg.startsWith("LEFT:")) {
                    String room = msg.substring(5);
                    removeJoinedRoom(room);
                    String leftRoom = "Left: ";
                    System.out.println(printBold(leftRoom) + room);
                } else {
                    System.out.println(msg);
                }
            }
        } catch (IOException e) {
            System.err.println("Connection lost");
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
                System.out.println("You cannot send messages at the moment.");
                System.out.println("Please join a room first (/join <room>)");
            }
        }
    }

    private void handleCommand(String input) {
        String[] parts = input.substring(1).split(" ", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "join":
                if (!arg.isBlank()) {
                    out.println("JOIN:" + arg);
                    String joinRoom = "\nJoining room: ";
                    System.out.println(printBold(joinRoom) + arg);
                } else {
                    System.out.println("Please specify a room to join.");
                    System.out.println("Usage: /join <room>");
                    System.out.println("Or for AI rooms: /join AI:<name>:<prompt>");
                }
                break;
            case "leave":
                if (currentRoom != null) {
                    out.println("LEAVE:" + currentRoom);
                    String leftRoom = "Leaving room: ";
                    System.out.println(printBold(leftRoom) + currentRoom);
                } else System.out.println("You're not currently in any room to leave.");
                break;
            case "rooms":
                showRooms();
                break;
            case "logout":
                running = false;
                out.println("LOGOUT");
                System.out.println("Logging out... Goodbye!");
                break;
            case "help":
                showHelp();
                break;
            default:
                System.out.println("Unknown command.");
                System.out.println("Type /help for a list of available commands.");
        }
    }

    private void printMessage(String room, String sender, String content) {
        String prefix = sender.equals("Bot") ? "🤖 " : "";
        String prefixMessage;
        if (room.equals(currentRoom)) {
            prefixMessage = prefix + sender + ": ";
        } else {
            prefixMessage = "[" + room + "]" + prefix + sender + ": ";
        }
        System.out.println(printBold(prefixMessage) + content);
    }

    private void updateAvailableRooms(String list) {
        roomsLock.writeLock().lock();
        try {
            availableRooms.clear();
            aiRooms.clear();

            if (list == null || list.isBlank()) {
                System.out.println("No rooms provided to update.");
                return;
            }
            for (String r : list.split(",")) {
                String room = r.trim();
                if (room.isEmpty()) continue;
                availableRooms.add(room);
                if (r.startsWith("AI")) aiRooms.add(r);
            }
            String showAvailableRooms = "Available rooms: ";
            System.out.println(printBold(showAvailableRooms) + availableRooms);
        } finally {
            roomsLock.writeLock().unlock();
        }
    }

    private void showRooms() {
        roomsLock.readLock().lock();
        try {
            String current = "Current: ", joined = "Joined: ", available = "Available: ";
            System.out.println(printBold(current) + (currentRoom != null ? currentRoom : "(none)"));
            System.out.println(printBold(joined) + joinedRooms);
            System.out.println(printBold(available) + availableRooms);
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


    private String printBold(String message) {
        return BOLD + message + RESET;
    }

    private void printSystemMessage(String room, String message) {
        String roomBold = "[" + room + "] SYSTEM: ";
        System.out.println(printBold(roomBold) + message);
    }

    private void showHelp() {
        String message = "\n==================== Available Commands ====================";
        System.out.println(printBold(message));
        System.out.println("/join <room>                - Join or create regular room");
        System.out.println("/join AI:<name>:<prompt>    - Join or create AI room");
        System.out.println("/leave                      - Leave current room");
        System.out.println("/rooms                      - Show current/joined/available rooms");
        System.out.println("/logout                     - Exit");
        System.out.println("/help                       - Show this help menu\n");
    }

    private void cleanup() {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }
}
