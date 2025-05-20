// ----- Server.java -----
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.locks.*;
import org.json.*;

public class Server {
    private static final int PORT = 8888;
    private static final String USERS_FILE = "auth.txt";
    private static final String AI_ENDPOINT = "http://localhost:11434/api/generate";

    private final Map<String, String> users = new HashMap<>();
    private final ReadWriteLock usersLock = new ReentrantReadWriteLock();
    private final Map<String, Room> rooms = new HashMap<>();
    private final ReadWriteLock roomsLock = new ReentrantReadWriteLock();

    public static void main(String[] args) {
        Server server = new Server();
        server.loadUsers();
        server.createDefaultRooms();
        server.start();
    }

    private void loadUsers() {
        usersLock.writeLock().lock();
        try {
            Path p = Paths.get(USERS_FILE);
            if (Files.exists(p)) {
                for (String line : Files.readAllLines(p)) {
                    String[] parts = line.split(":");
                    if (parts.length == 2) users.put(parts[0], parts[1]);
                }
            }
        } catch (IOException e) {
            System.err.println("Error loading users: " + e.getMessage());
        } finally {
            usersLock.writeLock().unlock();
        }
    }

    private void saveUsers() {
        usersLock.readLock().lock();
        try {
            List<String> lines = new ArrayList<>();
            users.forEach((u, p) -> lines.add(u + ":" + p));
            Files.write(Paths.get(USERS_FILE), lines);
        } catch (IOException e) {
            System.err.println("Error saving users: " + e.getMessage());
        } finally {
            usersLock.readLock().unlock();
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean registerUser(String user, String pass) {
        String hu = sha256(user), hp = sha256(pass);
        usersLock.writeLock().lock();
        try {
            users.put(hu, hp);
            saveUsers();
            return true;
        } finally {
            usersLock.writeLock().unlock();
        }
    }

    private void createDefaultRooms() {
        roomsLock.writeLock().lock();
        try {
            rooms.put("General", new Room("General", false, null));
            rooms.put("Random", new Room("Random", false, null));
            rooms.put("AI-Assistant", new Room("AI-Assistant", true,
                    "You are a helpful AI assistant in a chat room."));
        } finally {
            roomsLock.writeLock().unlock();
        }
    }

    private void start() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server created successfully.");
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
            out.println("👋 Welcome! Please enter your username and password to get started.");
            String line, username = null;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("LOGIN:")) {
                    String[] parts = line.substring(6).split(":", 2);
                    if (parts.length == 2) {
                        String user = parts[0], pass = parts[1];
                        String hu = sha256(user), hp = sha256(pass);
                        usersLock.readLock().lock();
                        boolean firstTime = !users.containsKey(hu);
                        usersLock.readLock().unlock();
                        if (firstTime) {
                            registerUser(user, pass);
                            System.out.println("New user successfully registered: " + user);
                            username = user;
                            out.println("AUTH_NEW:");
                            sendRoomList(out);
                            break;
                        } else if (!hp.equals(users.get(hu))) {
                            System.out.println("Login failed for user '" + user + "': Incorrect password.");
                            out.println("AUTH_FAIL:Invalid credentials");
                        } else {
                            System.out.println("User logged in successfully: " + user);
                            username = user;
                            out.println("AUTH_OK:Welcome back, " + username + "!");
                            sendRoomList(out);
                            break;
                        }
                    }
                }
            }
            if (username == null) {
                clientSocket.close();
                return;
            }

            ClientHandler handler = new ClientHandler(username, clientSocket, in, out);
            while ((line = in.readLine()) != null) {
                if ("LOGOUT".equals(line)) break;
                dispatch(line, handler);
            }
            handler.cleanup();
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        }
    }

    private void sendRoomList(PrintWriter out) {
        roomsLock.readLock().lock();
        try {
            StringBuilder sb = new StringBuilder("ROOMS:");
            for (String r : rooms.keySet()) sb.append(r).append(",");
            out.println(sb.toString());
        } finally {
            roomsLock.readLock().unlock();
        }
    }

    private void dispatch(String line, ClientHandler handler) {
        if (line.startsWith("JOIN:")) {
            String arg = line.substring(5);
            if (arg.startsWith("AI:")) {
                String[] parts = arg.split(":", 3);
                handler.createOrJoin(parts[1], true, parts.length == 3 ? parts[2] : null);
            } else {
                handler.createOrJoin(arg, false, null);
            }
        } else if (line.startsWith("LEAVE:")) {
            handler.leaveRoom(line.substring(6));
        } else if (line.startsWith("MESSAGE:")) {
            String[] parts = line.substring(8).split(":", 2);
            handler.sendMessage(parts[0], parts.length > 1 ? parts[1] : "");
        }
    }

    // Handles each connected user
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

        void createOrJoin(String roomName, boolean isAI, String prompt) {
            // Create if missing
            roomsLock.writeLock().lock();
            try {
                if (!rooms.containsKey(roomName)) {
                    if (isAI && (prompt == null || prompt.isBlank())) {
                        prompt = "You are a helpful assistant named Bot in a chat room.";
                    }
                    rooms.put(roomName, new Room(roomName, isAI, prompt));
                    out.println("CREATED:" + roomName);
                }
            } finally {
                roomsLock.writeLock().unlock();
            }
            // Join it
            roomsLock.readLock().lock();
            Room room = rooms.get(roomName);
            roomsLock.readLock().unlock();
            if (room != null) {
                room.addUser(this);
                joinedLock.writeLock().lock();
                joinedRooms.add(roomName);
                joinedLock.writeLock().unlock();
                room.broadcast(username + " joined", null);
                out.println("JOINED:" + roomName);
            } else {
                out.println("ERROR:Room not found: " + roomName);
            }
        }

        void leaveRoom(String roomName) {
            roomsLock.readLock().lock();
            Room room = rooms.get(roomName);
            roomsLock.readLock().unlock();
            joinedLock.writeLock().lock();
            try {
                if (room != null && joinedRooms.remove(roomName)) {
                    room.removeUser(this);
                    room.broadcast(username + " left", null);
                    out.println("LEFT:" + roomName);
                }
            } finally {
                joinedLock.writeLock().unlock();
            }
        }

        void sendMessage(String roomName, String msg) {
            roomsLock.readLock().lock();
            Room room = rooms.get(roomName);
            roomsLock.readLock().unlock();
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
            if (sender == null) out.println("SYSTEM:" + roomName + ":" + msg);
            else out.println("MESSAGE:" + roomName + ":" + sender + ":" + msg);
        }

        void cleanup() {
            joinedLock.writeLock().lock();
            try {
                for (String r : new ArrayList<>(joinedRooms)) {
                    leaveRoom(r);
                }
            } finally {
                joinedLock.writeLock().unlock();
            }
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    // Represents a chat room (normal or AI)
    private class Room {
        private final String name;
        private final boolean aiRoom;
        private final String aiPrompt;
        private final Set<ClientHandler> users = new HashSet<>();
        private final ReadWriteLock usersLock = new ReentrantReadWriteLock();

        Room(String name, boolean aiRoom, String aiPrompt) {
            this.name = name;
            this.aiRoom = aiRoom;
            this.aiPrompt = aiPrompt;
        }

        void addUser(ClientHandler ch) {
            usersLock.writeLock().lock();
            try { users.add(ch); }
            finally { usersLock.writeLock().unlock(); }
        }

        void removeUser(ClientHandler ch) {
            usersLock.writeLock().lock();
            try { users.remove(ch); }
            finally { usersLock.writeLock().unlock(); }
        }

        void broadcast(String msg, String sender) {
            // send to all
            usersLock.readLock().lock();
            try {
                for (ClientHandler u : users) {
                    u.receiveMessage(name, sender, msg);
                }
            } finally {
                usersLock.readLock().unlock();
            }
            // if AI room and user message, get AI reply immediately
            if (aiRoom && sender != null && !sender.equals("Bot")) {
                String aiReply = getAIResponse(msg);
                broadcast(aiReply, "Bot");
            }
        }

        private String getAIResponse(String lastUserMsg) {
            try {
                URL url = new URL(AI_ENDPOINT);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                JSONObject body = new JSONObject();
                body.put("model", "llama3.2:1b");
                // Basic prompt + last user message
                body.put("prompt", aiPrompt + "\nUser: " + lastUserMsg + "\nBot:");
                body.put("stream", false);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                if (conn.getResponseCode() != 200) return "Sorry, AI error.";
                StringBuilder resp = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) resp.append(line);
                }
                return new JSONObject(resp.toString()).getString("response").trim();
            } catch (Exception e) {
                return "Sorry, AI request failed.";
            }
        }
    }
}
