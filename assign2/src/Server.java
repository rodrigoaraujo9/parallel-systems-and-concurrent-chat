import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.json.*;

public class Server {
    private static final int PORT = 8888;
    private static final String USERS_FILE = "auth.txt";
    private static final String AI_ENDPOINT = "http://localhost:11434/api/generate";

    private final Map<String, String> users = new HashMap<>();
    private final ReadWriteLock usersLock = new ReentrantReadWriteLock();

    private final Map<String, Room> rooms = new HashMap<>();
    private final ReadWriteLock roomsLock = new ReentrantReadWriteLock();

    private final Map<String, ClientHandler> connectedUsers = new HashMap<>();
    private final ReadWriteLock connectedLock = new ReentrantReadWriteLock();

    public static void main(String[] args) {
        Server server = new Server();
        server.loadUsers();
        server.createDefaultRooms();
        server.start();
    }

    private void loadUsers() {
        Lock writeLock = usersLock.writeLock();
        writeLock.lock();
        try {
            Path p = Paths.get(USERS_FILE);
            if (Files.exists(p)) {
                Files.readAllLines(p).forEach(line -> {
                    String[] parts = line.split(":");
                    if (parts.length == 2) {
                        users.put(parts[0], parts[1]);
                    }
                });
                System.out.println("Loaded " + users.size() + " users.");
            } else {
                registerHashed("alice", "pass");
                registerHashed("bob", "pass");
                registerHashed("mariana", "pass");
                saveUsers();
                System.out.println("Created default users (hashed).");
            }
        } catch (IOException e) {
            System.err.println("Error loading users: " + e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    private void saveUsers() {
        Lock readLock = usersLock.readLock();
        readLock.lock();
        try {
            List<String> lines = new ArrayList<>();
            users.forEach((k, v) -> lines.add(k + ":" + v));
            Files.write(Paths.get(USERS_FILE), lines);
        } catch (IOException e) {
            System.err.println("Error saving users: " + e.getMessage());
        } finally {
            readLock.unlock();
        }
    }

    private boolean registerHashed(String user, String pass) {
        String hu = sha256(user);
        String hp = sha256(pass);
        users.put(hu, hp);
        return true;
    }

    private void createDefaultRooms() {
        Lock writeLock = roomsLock.writeLock();
        writeLock.lock();
        try {
            rooms.put("General", new Room("General", false, null));
            rooms.put("Random", new Room("Random", false, null));

            // Add a default AI room for testing
            rooms.put("AI-Assistant", new Room("AI-Assistant", true,
                    "You are a helpful AI assistant in a chat room. Be friendly, concise, and helpful to users."));

            System.out.println("Created default rooms: " + rooms.keySet());
        } finally {
            writeLock.unlock();
        }
    }

    private void start() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server listening on port " + PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                Thread.startVirtualThread(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private void handleClient(Socket clientSocket) {
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
        ) {
            out.println("AUTH:Welcome to the chat server.");
            String username = null;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("LOGIN:")) {
                    String[] parts = line.substring(6).split(":", 2);
                    if (parts.length == 2 && authenticate(parts[0], parts[1])) {
                        username = parts[0];
                        out.println("AUTH_OK:" + username);
                        sendRoomList(out);
                        break;
                    } else {
                        out.println("AUTH_FAIL:Invalid credentials");
                    }
                } else if (line.startsWith("REGISTER:")) {
                    String[] parts = line.substring(9).split(":", 2);
                    if (parts.length == 2 && registerUser(parts[0], parts[1])) {
                        username = parts[0];
                        out.println("AUTH_OK:" + username);
                        sendRoomList(out);
                        break;
                    } else {
                        out.println("AUTH_FAIL:Username already exists");
                    }
                }
            }
            if (username == null) {
                clientSocket.close();
                return;
            }
            ClientHandler handler = new ClientHandler(username, clientSocket, in, out);
            connectedLock.writeLock().lock();
            try {
                connectedUsers.put(username, handler);
            } finally {
                connectedLock.writeLock().unlock();
            }
            System.out.println("User connected: " + username);
            while ((line = in.readLine()) != null) {
                if (line.equals("LOGOUT")) break;
                dispatchCommand(line, handler);
            }
            connectedLock.writeLock().lock();
            try {
                connectedUsers.remove(username);
            } finally {
                connectedLock.writeLock().unlock();
            }
            handler.cleanup();
            System.out.println("User disconnected: " + username);
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        }
    }

    private void dispatchCommand(String line, ClientHandler handler) {
        if (line.startsWith("JOIN:")) {
            handler.joinRoom(line.substring(5));
        } else if (line.startsWith("CREATE:AI:")) {
            String[] parts = line.substring(10).split(":", 2);
            handler.createRoom(parts[0], true, parts.length > 1 ? parts[1] : null);
        } else if (line.startsWith("CREATE:")) {
            handler.createRoom(line.substring(7), false, null);
        } else if (line.startsWith("LEAVE:")) {
            handler.leaveRoom(line.substring(6));
        } else if (line.startsWith("MESSAGE:")) {
            String[] parts = line.substring(8).split(":", 2);
            handler.sendMessage(parts[0], parts.length > 1 ? parts[1] : "");
        }
    }

    private boolean authenticate(String user, String pass) {
        String hu = sha256(user);
        String hp = sha256(pass);
        usersLock.readLock().lock();
        try {
            return hp.equals(users.get(hu));
        } finally {
            usersLock.readLock().unlock();
        }
    }

    private boolean registerUser(String user, String pass) {
        String hu = sha256(user);
        String hp = sha256(pass);
        usersLock.writeLock().lock();
        try {
            if (users.containsKey(hu)) return false;
            users.put(hu, hp);
            saveUsers();
            return true;
        } finally {
            usersLock.writeLock().unlock();
        }
    }

    private void sendRoomList(PrintWriter out) {
        roomsLock.readLock().lock();
        try {
            StringBuilder sb = new StringBuilder("ROOMS:");
            rooms.keySet().forEach(room -> sb.append(room).append(","));
            out.println(sb.toString());
        } finally {
            roomsLock.readLock().unlock();
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 error", e);
        }
    }

    private class ClientHandler {
        private final String username;
        private final Socket socket;
        private final BufferedReader in;
        private final PrintWriter out;
        private final Set<String> joinedRooms = new HashSet<>();
        private final ReadWriteLock joinedLock = new ReentrantReadWriteLock();

        ClientHandler(String username, Socket socket, BufferedReader in, PrintWriter out) {
            this.username = username;
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        void joinRoom(String roomName) {
            Room room;
            roomsLock.readLock().lock();
            try {
                room = rooms.get(roomName);
            } finally {
                roomsLock.readLock().unlock();
            }
            if (room == null) {
                out.println("ERROR:Room not found: " + roomName);
                return;
            }
            room.addUser(this);
            joinedLock.writeLock().lock();
            try {
                joinedRooms.add(roomName);
            } finally {
                joinedLock.writeLock().unlock();
            }
            room.broadcast(username + " joined", null);
            out.println("JOINED:" + roomName);
            sendHistory(room);
        }

        void createRoom(String name, boolean isAI, String prompt) {
            roomsLock.writeLock().lock();
            try {
                if (rooms.containsKey(name)) {
                    out.println("ERROR:Room exists: " + name);
                    return;
                }
                // Set a default prompt if not provided for AI rooms
                if (isAI && (prompt == null || prompt.trim().isEmpty())) {
                    prompt = "You are a helpful assistant named Bot in a chat room. Respond to users in a helpful and concise manner.";
                }
                Room r = new Room(name, isAI, prompt);
                rooms.put(name, r);
                out.println("CREATED:" + name);
                joinRoom(name);
            } finally {
                roomsLock.writeLock().unlock();
            }
        }

        void leaveRoom(String roomName) {
            Room room;
            roomsLock.readLock().lock();
            try {
                room = rooms.get(roomName);
            } finally {
                roomsLock.readLock().unlock();
            }
            joinedLock.writeLock().lock();
            try {
                if (room != null && joinedRooms.contains(roomName)) {
                    room.removeUser(this);
                    joinedRooms.remove(roomName);
                    room.broadcast(username + " left", null);
                    out.println("LEFT:" + roomName);
                }
            } finally {
                joinedLock.writeLock().unlock();
            }
        }

        void sendMessage(String roomName, String msg) {
            Room room;
            roomsLock.readLock().lock();
            try {
                room = rooms.get(roomName);
            } finally {
                roomsLock.readLock().unlock();
            }
            joinedLock.readLock().lock();
            try {
                if (room != null && joinedRooms.contains(roomName)) {
                    room.broadcast(msg, username);
                }
            } finally {
                joinedLock.readLock().unlock();
            }
        }

        void receiveMessage(String roomName, String sender, String msg) {
            if (sender == null) {
                out.println("SYSTEM:" + roomName + ":" + msg);
            } else {
                out.println("MESSAGE:" + roomName + ":" + sender + ":" + msg);
            }
        }

        private void sendHistory(Room room) {
            room.getHistory().forEach(h -> out.println("HISTORY:" + room.getName() + ":" + h));
        }

        void cleanup() {
            joinedLock.writeLock().lock();
            try {
                new ArrayList<>(joinedRooms).forEach(this::leaveRoom);
            } finally {
                joinedLock.writeLock().unlock();
            }
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private class Room {
        private final String name;
        private final boolean aiRoom;
        private final String aiPrompt;
        private final List<String> history = new ArrayList<>();
        private final ReadWriteLock historyLock = new ReentrantReadWriteLock();
        private final Set<ClientHandler> users = new HashSet<>();
        private final ReadWriteLock usersLock = new ReentrantReadWriteLock();

        Room(String name, boolean aiRoom, String aiPrompt) {
            this.name = name;
            this.aiRoom = aiRoom;
            this.aiPrompt = aiPrompt;

            // If this is an AI room, send a welcome message from Bot
            if (aiRoom) {
                history.add("Bot: Hello! This is an AI-assisted chat room. I'm here to help with your conversation.");
            }
        }

        String getName() { return name; }

        void addUser(ClientHandler ch) {
            usersLock.writeLock().lock();
            try {
                users.add(ch);
            } finally {
                usersLock.writeLock().unlock();
            }
        }

        void removeUser(ClientHandler ch) {
            usersLock.writeLock().lock();
            try {
                users.remove(ch);
            } finally {
                usersLock.writeLock().unlock();
            }
        }

        void broadcast(String msg, String sender) {
            String fmt = (sender == null) ? "[" + msg + "]" : sender + ": " + msg;
            historyLock.writeLock().lock();
            try {
                history.add(fmt);
                // Trim history if it gets too long (optional)
                if (history.size() > 100) {
                    history.remove(0);
                }
            } finally {
                historyLock.writeLock().unlock();
            }
            usersLock.readLock().lock();
            try {
                users.forEach(u -> u.receiveMessage(name, sender, msg));
            } finally {
                usersLock.readLock().unlock();
            }

            // Handle AI response if this is an AI room and the message is from a user (not the Bot)
            if (aiRoom && sender != null && !sender.equals("Bot")) {
                CompletableFuture.runAsync(() -> {
                    try {
                        String botResponse = getAIResponse();
                        if (botResponse != null && !botResponse.isEmpty()) {
                            broadcast(botResponse, "Bot");
                        }
                    } catch (Exception e) {
                        System.err.println("AI Response Error: " + e.getMessage());
                        broadcast("Sorry, I encountered an error processing your request.", "Bot");
                    }
                });
            }
        }

        private String buildPrompt() {
            historyLock.readLock().lock();
            try {
                StringBuilder sb = new StringBuilder();

                // Add system prompt
                if (aiPrompt != null && !aiPrompt.isEmpty()) {
                    sb.append(aiPrompt).append("\n\n");
                } else {
                    sb.append("You are a helpful assistant named Bot in a chat room. Respond to users in a helpful and concise manner.\n\n");
                }

                // Add chat history
                List<String> contextHistory = history;
                // If history is very long, take only the most recent messages
                if (history.size() > 20) {
                    contextHistory = history.subList(history.size() - 20, history.size());
                }

                for (String msg : contextHistory) {
                    sb.append(msg).append("\n");
                }

                return sb.toString();
            } finally {
                historyLock.readLock().unlock();
            }
        }

        private String getAIResponse() {
            try {
                URL url = new URL(AI_ENDPOINT);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                // Create JSON request using org.json
                JSONObject requestBody = new JSONObject();
                requestBody.put("model", "llama3.2:1b");  // Use the 1B model as specified
                requestBody.put("prompt", buildPrompt());
                requestBody.put("stream", false);

                // Send request
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
                }

                // Check response status
                if (conn.getResponseCode() != 200) {
                    System.err.println("HTTP error: " + conn.getResponseCode());
                    System.err.println("Error message: " + conn.getResponseMessage());
                    return "Sorry, I'm having trouble connecting to my AI service.";
                }

                // Read response
                StringBuilder response = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                }

                // Parse JSON response using org.json
                JSONObject jsonResponse = new JSONObject(response.toString());
                String aiText = jsonResponse.getString("response");

                // Process the response to clean it up if needed
                aiText = aiText.trim();

                return aiText;
            } catch (Exception e) {
                System.err.println("AI Request Error: " + e.getMessage());
                e.printStackTrace();
                return "Sorry, I encountered an error and couldn't process your request.";
            }
        }

        List<String> getHistory() {
            historyLock.readLock().lock();
            try {
                return new ArrayList<>(history);
            } finally {
                historyLock.readLock().unlock();
            }
        }
    }
}