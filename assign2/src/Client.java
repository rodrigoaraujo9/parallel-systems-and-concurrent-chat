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
    private final ReadWriteLock roomsLock = new ReentrantReadWriteLock();
    private final Set<String> aiRooms = new HashSet<>();

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
        System.out.println(in.readLine());
        while (true) {
            System.out.println("1. Login\n2. Register");
            String choice = console.readLine();
            if (choice.equals("1") || choice.equals("2")) {
                System.out.print("Username: ");
                String user = console.readLine();
                System.out.print("Password: ");
                String pass = console.readLine();
                out.println((choice.equals("1") ? "LOGIN:" : "REGISTER:") + user + ":" + pass);
                break;
            }
            System.out.println("Invalid choice");
        }
        String response = in.readLine();
        if (response.startsWith("AUTH_OK:")) {
            username = response.substring(8);
            System.out.println("Welcome " + username);
            showHelp();
        } else {
            System.out.println("Auth failed: " + response.substring(9));
            System.exit(1);
        }
    }

    private void receiveMessages() {
        try {
            String message;
            while (running && (message = in.readLine()) != null) {
                if (message.startsWith("MESSAGE:")) {
                    String[] parts = message.substring(8).split(":", 3);
                    printMessage(parts[0], parts[1], parts[2]);
                } else if (message.startsWith("SYSTEM:")) {
                    String[] parts = message.substring(7).split(":", 2);
                    printSystem(parts[0], parts[1]);
                } else if (message.startsWith("ROOMS:")) {
                    String rooms = message.substring(6);
                    System.out.println("Available rooms: " + rooms);
                    // Detect AI rooms by name prefix
                    Arrays.stream(rooms.split(","))
                            .filter(room -> room.startsWith("AI"))
                            .forEach(aiRooms::add);
                } else if (message.startsWith("JOINED:")) {
                    currentRoom = message.substring(7);
                    addJoinedRoom(currentRoom);
                    System.out.println("Joined: " + currentRoom);
                    // If joining an AI room, show a helpful message
                    if (isAIRoom(currentRoom)) {
                        System.out.println("[INFO] This is an AI room. All messages will get responses from the AI Bot.");
                    }
                } else if (message.startsWith("LEFT:")) {
                    removeJoinedRoom(message.substring(5));
                    System.out.println("Left: " + message.substring(5));
                } else if (message.startsWith("CREATED:")) {
                    String roomName = message.substring(8);
                    System.out.println("Created room: " + roomName);
                    if (roomName.startsWith("AI")) {
                        aiRooms.add(roomName);
                    }
                } else if (message.startsWith("HISTORY:")) {
                    String[] parts = message.substring(8).split(":", 2);
                    System.out.println("[History] " + parts[1]);
                } else {
                    System.out.println(message);
                }
            }
        } catch (IOException e) {
            if (running) System.err.println("Connection lost");
        }
    }

    private void handleInput() throws IOException {
        String input;
        while (running && (input = console.readLine()) != null) {
            if (input.startsWith("/")) {
                handleCommand(input);
            } else if (currentRoom != null) {
                out.println("MESSAGE:" + currentRoom + ":" + input);
                if (isAIRoom(currentRoom)) {
                    System.out.println("Waiting for Bot response...");
                }
            } else {
                System.out.println("Join a room first");
            }
        }
    }

    private void handleCommand(String input) {
        String[] parts = input.substring(1).split(" ", 2);
        String command = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        switch (command) {
            case "join":
                if (!arg.isEmpty()) out.println("JOIN:" + arg);
                else System.out.println("Usage: /join <room>");
                break;

            case "create":
                if (!arg.isEmpty()) {
                    if (arg.startsWith("AI:")) {
                        String[] aiParts = arg.split(":", 3);
                        if (aiParts.length == 3) {
                            out.println("CREATE:AI:" + aiParts[1] + ":" + aiParts[2]);
                            aiRooms.add(aiParts[1]);
                        } else {
                            System.out.println("Usage: /create AI:<name>:<prompt>");
                        }
                    } else {
                        out.println("CREATE:" + arg);
                    }
                } else {
                    System.out.println("Usage: /create <name> or /create AI:<name>:<prompt>");
                }
                break;

            case "leave":
                if (currentRoom != null) out.println("LEAVE:" + currentRoom);
                else System.out.println("Not in a room");
                break;

            case "switch":
                if (!arg.isEmpty() && hasJoinedRoom(arg)) {
                    currentRoom = arg;
                    System.out.println("Switched to " + arg);
                    if (isAIRoom(currentRoom)) {
                        System.out.println("[INFO] This is an AI room. All messages will get responses from the AI Bot.");
                    }
                } else {
                    System.out.println("Invalid room or not joined");
                }
                break;

            case "rooms":
                System.out.println("Joined: " + getJoinedRooms());
                break;

            case "logout":
                running = false;
                out.println("LOGOUT");
                break;

            case "help":
                showHelp();
                break;

            default:
                System.out.println("Unknown command");
        }
    }

    private void printMessage(String room, String sender, String content) {
        String botPrefix = sender.equals("Bot") ? "🤖 " : "";

        if (room.equals(currentRoom)) {
            System.out.println(botPrefix + sender + ": " + content);
        } else {
            System.out.println("[" + room + "] " + botPrefix + sender + ": " + content);
        }
    }

    private void printSystem(String room, String message) {
        System.out.println("[" + room + "] System: " + message);
    }

    private void addJoinedRoom(String room) {
        roomsLock.writeLock().lock();
        try {
            joinedRooms.add(room);
        } finally {
            roomsLock.writeLock().unlock();
        }
    }

    private void removeJoinedRoom(String room) {
        roomsLock.writeLock().lock();
        try {
            joinedRooms.remove(room);
            if (room.equals(currentRoom)) currentRoom = null;
        } finally {
            roomsLock.writeLock().unlock();
        }
    }

    private boolean hasJoinedRoom(String room) {
        roomsLock.readLock().lock();
        try {
            return joinedRooms.contains(room);
        } finally {
            roomsLock.readLock().unlock();
        }
    }

    private String getJoinedRooms() {
        roomsLock.readLock().lock();
        try {
            return String.join(", ", joinedRooms);
        } finally {
            roomsLock.readLock().unlock();
        }
    }

    private boolean isAIRoom(String roomName) {
        return roomName != null && (roomName.startsWith("AI") || aiRooms.contains(roomName));
    }

    private void showHelp() {
        System.out.println("Commands:");
        System.out.println("/join <room> - Join a room");
        System.out.println("/create <name> - Create regular room");
        System.out.println("/create AI:<name>:<prompt> - Create AI room with custom prompt");
        System.out.println("/leave - Leave current room");
        System.out.println("/switch <room> - Switch to joined room");
        System.out.println("/rooms - List joined rooms");
        System.out.println("/logout - Exit");
        System.out.println("/help - Show this help");
    }

    private void cleanup() {
        try {
            if (socket != null) socket.close();
            if (in != null) in.close();
            if (out != null) out.close();
        } catch (IOException e) {
            System.err.println("Cleanup error: " + e.getMessage());
        }
    }
}