// Server.java
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.locks.*;
import javax.net.ssl.*;
import org.json.*;

public class Server {
    private static final int PORT = 8888;
    private static final String USERS_FILE = "auth.txt";
    private static final String SESSION_FILE = "sessions.txt";
    private static final String AI_ENDPOINT = "http://localhost:11434/api/generate";
    private static final String KEYSTORE_PATH = "chatserver.jks";
    private static final String KEYSTORE_PASSWORD = "password";
    private static final int MAX_HISTORY_PER_ROOM = 100;
    private static final int MAX_USERNAME_LENGTH = 50;
    private static final int MAX_ROOM_NAME_LENGTH = 50;
    private static final int MAX_MESSAGE_LENGTH = 1000;

    private static final long TOKEN_EXPIRATION = 24 * 60 * 60 * 1000;

    // Single global lock for coordinated operations
    private final ReadWriteLock globalLock = new ReentrantReadWriteLock();

    private SSLContext sslContext;
    private final Map<String, String> users = new HashMap<>();
    private final Map<String, Room> rooms = new HashMap<>();
    private final Map<String, ClientHandler> connectedUsers = new HashMap<>();
    private final Map<String, String> userTokens = new HashMap<>();
    private final Map<String, String> tokenToUser = new HashMap<>();
    private final Map<String, Long> tokenExpirations = new HashMap<>();
    private final Map<String, Set<String>> userRooms = new HashMap<>();

    // Message buffer for offline users
    private final Map<String, List<QueuedMessage>> messageBuffer = new HashMap<>();

    public static void main(String[] args) {
        Server server = new Server();
        server.setupSSLContext();
        server.loadUsers();
        server.loadSessions();
        server.createDefaultRooms();
        server.start();
    }

    private void removeSessionForUser(String username) {
        globalLock.writeLock().lock();
        try {
            String token = userTokens.remove(username);
            if (token != null) {
                tokenToUser.remove(token);
                tokenExpirations.remove(token);
            }
            saveSessions();
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    private void setupSSLContext() {
        File keyStoreFile = new File(KEYSTORE_PATH);
        if (!keyStoreFile.exists()) {
            System.err.println("Error: Keystore file " + KEYSTORE_PATH + " does not exist.");
            System.err.println("Please run:\nkeytool -genkeypair -alias chatserver -keyalg RSA -keysize 2048 "
                    + "-validity 365 -keystore " + KEYSTORE_PATH + " -storepass " + KEYSTORE_PASSWORD);
            System.exit(1);
        }
        try {
            KeyStore ks = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(KEYSTORE_PATH)) {
                ks.load(fis, KEYSTORE_PASSWORD.toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, KEYSTORE_PASSWORD.toCharArray());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        } catch (Exception e) {
            System.err.println("Failed to set up SSLContext: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void loadUsers() {
        globalLock.writeLock().lock();
        try {
            Path p = Paths.get(USERS_FILE);
            if (Files.exists(p)) {
                for (String line : Files.readAllLines(p)) {
                    String[] parts = line.split(":", 2);
                    if (parts.length == 2) {
                        users.put(parts[0], parts[1]);
                    }
                }
                System.out.println("Loaded " + users.size() + " users from file.");
            }
        } catch (IOException e) {
            System.err.println("Error loading users: " + e.getMessage());
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    private void saveUsers() {
        // Must be called under write lock
        try {
            List<String> lines = new ArrayList<>();
            users.forEach((u, p) -> lines.add(u + ":" + p));
            Files.write(Paths.get(USERS_FILE), lines);
        } catch (IOException e) {
            System.err.println("Error saving users: " + e.getMessage());
        }
    }

    private void loadSessions() {
        globalLock.writeLock().lock();
        try {
            Path p = Paths.get(SESSION_FILE);
            if (Files.exists(p)) {
                for (String line : Files.readAllLines(p)) {
                    String[] parts = line.split(":", 4);
                    if (parts.length >= 3) {
                        String username = parts[0], token = parts[1];
                        long expiration = Long.parseLong(parts[2]);
                        if (expiration < System.currentTimeMillis()) continue;

                        userTokens.put(username, token);
                        tokenToUser.put(token, username);
                        tokenExpirations.put(token, expiration);

                        if (parts.length == 4 && !parts[3].isEmpty()) {
                            userRooms.put(username,
                                    new HashSet<>(Arrays.asList(parts[3].split(","))));
                        } else {
                            userRooms.put(username, new HashSet<>());
                        }

                        // Initialize message buffer
                        messageBuffer.put(username, new ArrayList<>());
                    }
                }
                System.out.println("Loaded " + userTokens.size() + " active sessions.");
            }
        } catch (IOException e) {
            System.err.println("Error loading sessions: " + e.getMessage());
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    private void saveSessions() {
        // Must be called under write lock
        try {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, String> entry : userTokens.entrySet()) {
                String username = entry.getKey(), token = entry.getValue();
                Long expiration = tokenExpirations.get(token);
                if (expiration == null || expiration < System.currentTimeMillis()) continue;

                StringBuilder sb = new StringBuilder();
                sb.append(username).append(":").append(token).append(":").append(expiration);
                Set<String> rooms = userRooms.get(username);
                if (rooms != null && !rooms.isEmpty()) {
                    sb.append(":").append(String.join(",", rooms));
                }
                lines.add(sb.toString());
            }
            Files.write(Paths.get(SESSION_FILE), lines);
        } catch (IOException e) {
            System.err.println("Error saving sessions: " + e.getMessage());
        }
    }

    private String sanitizeInput(String input, int maxLength) {
        if (input == null) return "";
        // Remove control characters and limit length
        String cleaned = input.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "")
                .trim();
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }

    private boolean isValidUsername(String username) {
        if (username == null || username.trim().isEmpty()) return false;
        String clean = sanitizeInput(username, MAX_USERNAME_LENGTH);
        return clean.length() >= 3 && clean.matches("[a-zA-Z0-9_-]+");
    }

    private boolean isValidRoomName(String roomName) {
        if (roomName == null || roomName.trim().isEmpty()) return false;
        String clean = sanitizeInput(roomName, MAX_ROOM_NAME_LENGTH);
        return clean.length() >= 1 && clean.matches("[a-zA-Z0-9_-]+");
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
        // Must be called under write lock
        users.put(user, sha256(pass)); // Store username plaintext, hash password
        saveUsers();
        return true;
    }

    private void createDefaultRooms() {
        globalLock.writeLock().lock();
        try {
            rooms.put("General", new Room("General", false, null));
            rooms.put("Random", new Room("Random", false, null));
            rooms.put("AI-Assistant", new Room("AI-Assistant", true,
                    "You are a helpful AI assistant in a chat room."));
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    private String generateToken() {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getEncoder().encodeToString(randomBytes);
    }

    private String createUserToken(String username) {
        // Must be called under write lock
        String token = generateToken();
        long expiration = System.currentTimeMillis() + TOKEN_EXPIRATION;

        userTokens.put(username, token);
        tokenToUser.put(token, username);
        tokenExpirations.put(token, expiration);
        saveSessions();

        return token;
    }

    private String validateToken(String token) {
        if (token == null) return null;

        globalLock.readLock().lock();
        try {
            String user = tokenToUser.get(token);
            if (user == null) return null;

            Long exp = tokenExpirations.get(token);
            if (exp == null || exp < System.currentTimeMillis()) {
                return null; // Let cleanup task handle expired tokens
            }
            return user;
        } finally {
            globalLock.readLock().unlock();
        }
    }

    private void start() {
        try {
            SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
            SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(PORT);
            serverSocket.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});

            System.out.println("Server listening on port " + PORT);
            Thread.startVirtualThread(this::sessionCleanupTask);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                Thread.startVirtualThread(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private void sessionCleanupTask() {
        while (true) {
            try {
                Thread.sleep(3_600_000); // 1 hour
                cleanupExpiredSessions();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        globalLock.writeLock().lock();
        try {
            List<String> expired = new ArrayList<>();
            for (Map.Entry<String, Long> e : tokenExpirations.entrySet()) {
                if (e.getValue() < now) expired.add(e.getKey());
            }

            for (String t : expired) {
                String u = tokenToUser.remove(t);
                tokenExpirations.remove(t);
                if (u != null) userTokens.remove(u);
            }

            if (!expired.isEmpty()) {
                System.out.println("Cleaned " + expired.size() + " expired sessions");
                saveSessions();
            }
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    private void handleClient(Socket clientSocket) {
        try (
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(clientSocket.getInputStream()));
                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
        ) {
            out.println("👋 Welcome! Please login or provide a session token.");
            String line, username = null;
            ClientHandler handler = null;

            // Authentication phase
            while ((line = in.readLine()) != null) {
                if (line.startsWith("LOGIN:")) {
                    String[] parts = line.substring(6).split(":", 2);
                    if (parts.length == 2) {
                        String user = sanitizeInput(parts[0], MAX_USERNAME_LENGTH);
                        String pass = parts[1];

                        if (!isValidUsername(user)) {
                            out.println("AUTH_FAIL:Invalid username format");
                            continue;
                        }

                        globalLock.writeLock().lock();
                        try {
                            boolean isNew = !users.containsKey(user);
                            if (isNew) {
                                registerUser(user, pass);
                                username = user;
                                String token = createUserToken(user);
                                out.println("AUTH_NEW:" + token);
                                userRooms.put(user, new HashSet<>());
                                messageBuffer.put(user, new ArrayList<>());
                                sendRoomList(out);
                                break;
                            } else if (!sha256(pass).equals(users.get(user))) {
                                out.println("AUTH_FAIL:Invalid credentials");
                            } else {
                                username = user;
                                String token = createUserToken(user);
                                out.println("AUTH_OK:" + token);
                                messageBuffer.putIfAbsent(user, new ArrayList<>());
                                sendRoomList(out);
                                break;
                            }
                        } finally {
                            globalLock.writeLock().unlock();
                        }
                    }
                } else if (line.startsWith("TOKEN:")) {
                    String token = line.substring(6);
                    String user = validateToken(token);
                    if (user != null) {
                        username = user;
                        out.println("SESSION_RESUMED:" + user);

                        globalLock.writeLock().lock();
                        try {
                            messageBuffer.putIfAbsent(user, new ArrayList<>());
                            sendRoomList(out);
                            handler = new ClientHandler(user, clientSocket, in, out);

                            ClientHandler old = connectedUsers.put(user, handler);
                            if (old != null) old.cleanup(false);

                            // Rejoin saved rooms and replay history
                            Set<String> saved = new HashSet<>(userRooms.getOrDefault(user, Set.of()));
                            for (String rn : saved) {
                                Room r = rooms.get(rn);
                                if (r != null) {
                                    handler.silentlyJoinRoom(r);
                                    out.println("REJOINED:" + rn);
                                    // Send room history
                                    r.sendHistoryTo(handler);
                                }
                            }

                            // Replay buffered messages
                            handler.replayBufferedMessages();
                        } finally {
                            globalLock.writeLock().unlock();
                        }
                        break;
                    } else {
                        out.println("AUTH_FAIL:Invalid or expired token");
                    }
                }
            }

            if (username == null) {
                clientSocket.close();
                return;
            }

            if (handler == null) {
                globalLock.writeLock().lock();
                try {
                    handler = new ClientHandler(username, clientSocket, in, out);
                    ClientHandler old = connectedUsers.put(username, handler);
                    if (old != null) old.cleanup(false);
                } finally {
                    globalLock.writeLock().unlock();
                }
            }

            // Message handling phase
            while ((line = in.readLine()) != null) {
                if ("LOGOUT".equals(line)) {
                    removeSessionForUser(username);
                    out.println("BYE");
                    break;
                }
                dispatch(line, handler);
            }
            handler.cleanup(true);

        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        }
    }

    private void sendRoomList(PrintWriter out) {
        // Must be called under lock
        StringBuilder sb = new StringBuilder("ROOMS:");
        for (Room r : rooms.values()) {
            sb.append(r.getName());
            if (r.isAiRoom()) sb.append(":AI");
            sb.append(",");
        }
        out.println(sb);
    }

    private void dispatch(String line, ClientHandler handler) {
        if (line.startsWith("JOIN:")) {
            String arg = line.substring(5);
            if (arg.startsWith("AI:")) {
                String[] p = arg.split(":", 3);
                if (p.length >= 2) {
                    String roomName = sanitizeInput(p[1], MAX_ROOM_NAME_LENGTH);
                    if (isValidRoomName(roomName)) {
                        handler.createOrJoin(roomName, true, p.length == 3 ? p[2] : null);
                    }
                }
            } else {
                String roomName = sanitizeInput(arg, MAX_ROOM_NAME_LENGTH);
                if (isValidRoomName(roomName)) {
                    handler.createOrJoin(roomName, false, null);
                }
            }
        } else if (line.startsWith("LEAVE:")) {
            handler.leaveRoom(line.substring(6));
        } else if (line.startsWith("MESSAGE:")) {
            String[] p = line.substring(8).split(":", 2);
            if (p.length == 2) {
                String msg = sanitizeInput(p[1], MAX_MESSAGE_LENGTH);
                handler.sendMessage(p[0], msg);
            }
        } else if (line.startsWith("ACK:")) {
            handler.handleAck(line.substring(4));
        }
    }

    // Represents a queued message for later delivery
    private static class QueuedMessage {
        final String room, sender, msg, msgId;
        final long timestamp;

        QueuedMessage(String r, String s, String m, String id) {
            room = r; sender = s; msg = m; msgId = id;
            timestamp = System.currentTimeMillis();
        }
    }

    private class ClientHandler {
        private final String username;
        private final Socket socket;
        private final BufferedReader in;
        private final PrintWriter out;
        private final Set<String> joinedRooms = new HashSet<>();
        private volatile boolean active = true;
        private final Map<String, QueuedMessage> pending = new HashMap<>();

        ClientHandler(String username, Socket socket,
                      BufferedReader in, PrintWriter out) {
            this.username = username; this.socket = socket;
            this.in = in; this.out = out;
        }

        void handleAck(String msgId) {
            globalLock.writeLock().lock();
            try {
                pending.remove(msgId);
            } finally {
                globalLock.writeLock().unlock();
            }
        }

        void silentlyJoinRoom(Room room) {
            // Must be called under global lock
            room.addUser(this);
            joinedRooms.add(room.getName());
            updateUserRooms();
        }

        void createOrJoin(String name, boolean isAI, String prompt) {
            globalLock.writeLock().lock();
            try {
                if (!rooms.containsKey(name)) {
                    rooms.put(name, new Room(name, isAI,
                            prompt == null || prompt.isBlank()
                                    ? "You are a helpful assistant named Bot."
                                    : prompt));
                    out.println("CREATED:" + name);
                }

                Room room = rooms.get(name);
                if (room != null) {
                    room.addUser(this);
                    joinedRooms.add(name);
                    updateUserRooms();
                    room.broadcast(username + " joined", null);
                    out.println("JOINED:" + name);
                    // Send room history
                    room.sendHistoryTo(this);
                }
            } finally {
                globalLock.writeLock().unlock();
            }
        }

        void leaveRoom(String name) {
            globalLock.writeLock().lock();
            try {
                Room room = rooms.get(name);
                if (room != null && joinedRooms.remove(name)) {
                    room.removeUser(this);
                    room.broadcast(username + " left", null);
                    out.println("LEFT:" + name);
                    updateUserRooms();
                }
            } finally {
                globalLock.writeLock().unlock();
            }
        }

        void sendMessage(String roomName, String msg) {
            globalLock.writeLock().lock();
            try {
                Room room = rooms.get(roomName);
                if (room != null && joinedRooms.contains(roomName)) {
                    String msgId = UUID.randomUUID().toString().substring(0, 8);
                    QueuedMessage qm = new QueuedMessage(roomName, username, msg, msgId);
                    pending.put(msgId, qm);
                    room.broadcast(msg, username, msgId);
                }
            } finally {
                globalLock.writeLock().unlock();
            }
        }

        void receiveMessage(String roomName, String sender, String msg, String msgId) {
            if (active) {
                if (sender == null) {
                    out.println("SYSTEM:" + roomName + ":" + msg);
                } else {
                    out.println("MESSAGE:" + roomName + ":" + sender + ":" + msg + ":" + msgId);
                }
            } else {
                // User disconnected—buffer the message
                messageBuffer.get(username).add(new QueuedMessage(roomName, sender, msg, msgId));
            }
        }

        void replayBufferedMessages() {
            // Must be called under global lock
            List<QueuedMessage> buf = messageBuffer.get(username);
            for (QueuedMessage qm : new ArrayList<>(buf)) {
                if (qm.sender == null) {
                    out.println("SYSTEM:" + qm.room + ":" + qm.msg);
                } else {
                    out.println("MESSAGE:" + qm.room + ":" + qm.sender + ":" + qm.msg + ":" + qm.msgId);
                }
            }
            buf.clear();
        }

        private void updateUserRooms() {
            // Must be called under global lock
            userRooms.put(username, new HashSet<>(joinedRooms));
            saveSessions();
        }

        void cleanup(boolean removeFromRooms) {
            active = false;
            globalLock.writeLock().lock();
            try {
                if (removeFromRooms) {
                    if (connectedUsers.get(username) == this) {
                        connectedUsers.remove(username);
                    }

                    for (String r : new ArrayList<>(joinedRooms)) {
                        Room room = rooms.get(r);
                        if (room != null) {
                            room.removeUser(this);
                            room.broadcast(username + " disconnected", null);
                        }
                    }
                    joinedRooms.clear();
                }
            } finally {
                globalLock.writeLock().unlock();
            }

            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private class Room {
        private final String name;
        private final boolean aiRoom;
        private final String aiPrompt;
        private final Set<ClientHandler> users = new HashSet<>();
        private final List<QueuedMessage> messageHistory = new ArrayList<>();

        Room(String name, boolean aiRoom, String aiPrompt) {
            this.name = name; this.aiRoom = aiRoom; this.aiPrompt = aiPrompt;
        }

        String getName() { return name; }
        boolean isAiRoom() { return aiRoom; }

        void addUser(ClientHandler ch) {
            // Must be called under global lock
            users.add(ch);
        }

        void removeUser(ClientHandler ch) {
            // Must be called under global lock
            users.remove(ch);
        }

        void sendHistoryTo(ClientHandler user) {
            // Must be called under global lock
            for (QueuedMessage msg : messageHistory) {
                if (msg.sender == null) {
                    user.out.println("SYSTEM:" + msg.room + ":" + msg.msg);
                } else {
                    user.out.println("MESSAGE:" + msg.room + ":" + msg.sender + ":" + msg.msg + ":" + msg.msgId);
                }
            }
        }

        void broadcast(String msg, String sender) {
            broadcast(msg, sender, UUID.randomUUID().toString().substring(0, 8));
        }

        void broadcast(String msg, String sender, String msgId) {
            // Must be called under global lock
            QueuedMessage qm = new QueuedMessage(name, sender, msg, msgId);

            // Add to history
            messageHistory.add(qm);
            if (messageHistory.size() > MAX_HISTORY_PER_ROOM) {
                messageHistory.remove(0);
            }

            // Send to connected users
            for (ClientHandler u : users) {
                u.receiveMessage(name, sender, msg, msgId);
            }

            // Trigger AI response if this is an AI room and message is from a user
            if (aiRoom && sender != null && !"Bot".equals(sender)) {
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
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);

                JSONObject body = new JSONObject();
                body.put("model", "llama3.2:1b");
                body.put("prompt", aiPrompt + "\nUser: " + lastUserMsg + "\nBot:");
                body.put("stream", false);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                if (conn.getResponseCode() != 200) {
                    System.err.println("AI API error: " + conn.getResponseCode());
                    return "Sorry, AI service is temporarily unavailable.";
                }

                StringBuilder resp = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) resp.append(line);
                }

                String response = new JSONObject(resp.toString()).getString("response").trim();
                return response.isEmpty() ? "I'm not sure how to respond to that." : response;

            } catch (Exception e) {
                System.err.println("AI request failed: " + e.getMessage());
                return "Sorry, I'm having trouble connecting to my AI service right now.";
            }
        }
    }
}