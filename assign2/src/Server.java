// Server.java
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.*;
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

    private static final long TOKEN_EXPIRATION = 24 * 60 * 60 * 1000;

    private SSLContext sslContext;
    private final Map<String, String> users = new HashMap<>();
    private final ReadWriteLock usersLock = new ReentrantReadWriteLock();
    private final Map<String, Room> rooms = new HashMap<>();
    private final ReadWriteLock roomsLock = new ReentrantReadWriteLock();
    private final Map<String, ClientHandler> connectedUsers = new HashMap<>();
    private final ReadWriteLock connectedLock = new ReentrantReadWriteLock();
    private final Map<String, String> userTokens = new HashMap<>();
    private final Map<String, String> tokenToUser = new HashMap<>();
    private final Map<String, Long> tokenExpirations = new HashMap<>();
    private final Map<String, Set<String>> userRooms = new HashMap<>();
    private final ReadWriteLock tokenLock = new ReentrantReadWriteLock();
    private final ReadWriteLock userRoomsLock = new ReentrantReadWriteLock();

    // New: buffer of undelivered messages per user
    private final ConcurrentMap<String, Queue<QueuedMessage>> messageBuffer = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        Server server = new Server();
        server.setupSSLContext();
        server.loadUsers();
        server.loadSessions();
        server.createDefaultRooms();
        server.start();
    }

    private void removeSessionForUser(String username) {
        tokenLock.writeLock().lock();
        try {
            String token = userTokens.remove(username);
            if (token != null) {
                tokenToUser.remove(token);
                tokenExpirations.remove(token);
            }
            saveSessions();
        } finally {
            tokenLock.writeLock().unlock();
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
        usersLock.writeLock().lock();
        try {
            Path p = Paths.get(USERS_FILE);
            if (Files.exists(p)) {
                for (String line : Files.readAllLines(p)) {
                    String[] parts = line.split(":");
                    if (parts.length == 2) users.put(parts[0], parts[1]);
                }
                System.out.println("Loaded " + users.size() + " users from file.");
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

    private void loadSessions() {
        tokenLock.writeLock().lock();
        userRoomsLock.writeLock().lock();
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
                        // ensure buffer exists
                        messageBuffer.putIfAbsent(username, new ConcurrentLinkedQueue<>());
                    }
                }
                System.out.println("Loaded " + userTokens.size() + " active sessions.");
            }
        } catch (IOException e) {
            System.err.println("Error loading sessions: " + e.getMessage());
        } finally {
            userRoomsLock.writeLock().unlock();
            tokenLock.writeLock().unlock();
        }
    }

    private void saveSessions() {
        tokenLock.readLock().lock();
        userRoomsLock.readLock().lock();
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
        } finally {
            userRoomsLock.readLock().unlock();
            tokenLock.readLock().unlock();
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

    private String generateToken() {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getEncoder().encodeToString(randomBytes);
    }

    private String createUserToken(String username) {
        String token = generateToken();
        long expiration = System.currentTimeMillis() + TOKEN_EXPIRATION;
        tokenLock.writeLock().lock();
        try {
            userTokens.put(username, token);
            tokenToUser.put(token, username);
            tokenExpirations.put(token, expiration);
            saveSessions();
        } finally {
            tokenLock.writeLock().unlock();
        }
        return token;
    }

    private String validateToken(String token) {
        if (token == null) return null;
        tokenLock.readLock().lock();
        try {
            String user = tokenToUser.get(token);
            if (user == null) return null;
            Long exp = tokenExpirations.get(token);
            if (exp == null || exp < System.currentTimeMillis()) {
                // expired: clean up under write lock
                tokenLock.readLock().unlock();
                tokenLock.writeLock().lock();
                try {
                    tokenToUser.remove(token);
                    tokenExpirations.remove(token);
                    userTokens.remove(user);
                    saveSessions();
                } finally {
                    tokenLock.writeLock().unlock();
                    tokenLock.readLock().lock();
                }
                return null;
            }
            return user;
        } finally {
            tokenLock.readLock().unlock();
        }
    }

    private void start() {
        try {
            SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
            SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(PORT);
            serverSocket.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
            // prioritize ciphers...
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
                Thread.sleep(3_600_000);
                cleanupExpiredSessions();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        tokenLock.writeLock().lock();
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
                System.out.println("Cleaned " + expired.size() + " sessions");
                saveSessions();
            }
        } finally {
            tokenLock.writeLock().unlock();
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

            while ((line = in.readLine()) != null) {
                if (line.startsWith("LOGIN:")) {
                    String[] parts = line.substring(6).split(":", 2);
                    if (parts.length == 2) {
                        String user = parts[0], pass = parts[1];
                        String hu = sha256(user), hp = sha256(pass);
                        boolean isNew;
                        usersLock.readLock().lock();
                        try {
                            isNew = !users.containsKey(hu);
                        } finally {
                            usersLock.readLock().unlock();
                        }
                        if (isNew) {
                            registerUser(user, pass);
                            username = user;
                            String token = createUserToken(user);
                            out.println("AUTH_NEW:" + token);
                            userRoomsLock.writeLock().lock();
                            try { userRooms.put(user, new HashSet<>()); }
                            finally { userRoomsLock.writeLock().unlock(); }
                            messageBuffer.putIfAbsent(user, new ConcurrentLinkedQueue<>());
                            sendRoomList(out);
                            break;
                        } else if (!hp.equals(users.get(hu))) {
                            out.println("AUTH_FAIL:Invalid credentials");
                        } else {
                            username = user;
                            String token = createUserToken(user);
                            out.println("AUTH_OK:" + token);
                            messageBuffer.putIfAbsent(user, new ConcurrentLinkedQueue<>());
                            sendRoomList(out);
                            break;
                        }
                    }
                } else if (line.startsWith("TOKEN:")) {
                    String token = line.substring(6);
                    String user = validateToken(token);
                    if (user != null) {
                        username = user;
                        out.println("SESSION_RESUMED:" + user);
                        messageBuffer.putIfAbsent(user, new ConcurrentLinkedQueue<>());
                        sendRoomList(out);
                        handler = new ClientHandler(user, clientSocket, in, out);
                        connectedLock.writeLock().lock();
                        try {
                            ClientHandler old = connectedUsers.put(user, handler);
                            if (old != null) old.cleanup(false);
                        } finally { connectedLock.writeLock().unlock(); }
                        // rejoin saved rooms
                        Set<String> saved;
                        userRoomsLock.readLock().lock();
                        try { saved = new HashSet<>(userRooms.getOrDefault(user, Set.of())); }
                        finally { userRoomsLock.readLock().unlock(); }
                        for (String rn : saved) {
                            roomsLock.readLock().lock();
                            Room r = rooms.get(rn);
                            roomsLock.readLock().unlock();
                            if (r != null) {
                                handler.silentlyJoinRoom(r);
                                out.println("REJOINED:" + rn);
                            }
                        }
                        // replay buffered messages
                        handler.replayBufferedMessages();
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
                handler = new ClientHandler(username, clientSocket, in, out);
                connectedLock.writeLock().lock();
                try {
                    ClientHandler old = connectedUsers.put(username, handler);
                    if (old != null) old.cleanup(false);
                } finally { connectedLock.writeLock().unlock(); }
            }

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
        roomsLock.readLock().lock();
        try {
            StringBuilder sb = new StringBuilder("ROOMS:");
            for (Room r : rooms.values()) {
                sb.append(r.getName());
                if (r.isAiRoom()) sb.append(":AI");
                sb.append(",");
            }
            out.println(sb);
        } finally {
            roomsLock.readLock().unlock();
        }
    }

    private void dispatch(String line, ClientHandler handler) {
        if (line.startsWith("JOIN:")) {
            String arg = line.substring(5);
            if (arg.startsWith("AI:")) {
                String[] p = arg.split(":", 3);
                handler.createOrJoin(p[1], true, p.length==3 ? p[2] : null);
            } else {
                handler.createOrJoin(arg, false, null);
            }
        } else if (line.startsWith("LEAVE:")) {
            handler.leaveRoom(line.substring(6));
        } else if (line.startsWith("MESSAGE:")) {
            String[] p = line.substring(8).split(":",2);
            handler.sendMessage(p[0], p.length>1 ? p[1] : "");
        } else if (line.startsWith("ACK:")) {
            handler.handleAck(line.substring(4));
        }
    }


    // Represents a queued message for later delivery
    private static class QueuedMessage {
        final String room, sender, msg, msgId;
        QueuedMessage(String r, String s, String m, String id) {
            room = r; sender = s; msg = m; msgId = id;
        }
    }

    private class ClientHandler {
        private final String username;
        private final Socket socket;
        private final BufferedReader in;
        private final PrintWriter out;
        private final Set<String> joinedRooms = new HashSet<>();
        private final ReadWriteLock joinedLock = new ReentrantReadWriteLock();
        private volatile boolean active = true;
        // pending ACKs: msgId -> QueuedMessage
        private final Map<String,QueuedMessage> pending = new ConcurrentHashMap<>();

        ClientHandler(String username, Socket socket,
                      BufferedReader in, PrintWriter out) {
            this.username = username; this.socket = socket;
            this.in = in; this.out = out;
        }

        void handleAck(String msgId) {
            // ACK received; stop retrying or remove from pending
            pending.remove(msgId);
        }

        void silentlyJoinRoom(Room room) {
            room.addUser(this);
            joinedLock.writeLock().lock();
            try { joinedRooms.add(room.getName()); }
            finally { joinedLock.writeLock().unlock(); }
            updateUserRooms();
        }

        void createOrJoin(String name, boolean isAI, String prompt) {
            roomsLock.writeLock().lock();
            try {
                if (!rooms.containsKey(name)) {
                    rooms.put(name, new Room(name, isAI,
                            prompt==null||prompt.isBlank()
                                    ? "You are a helpful assistant named Bot."
                                    : prompt));
                    out.println("CREATED:" + name);
                }
            } finally { roomsLock.writeLock().unlock(); }
            roomsLock.readLock().lock();
            Room room = rooms.get(name);
            roomsLock.readLock().unlock();
            if (room != null) {
                room.addUser(this);
                joinedLock.writeLock().lock();
                try { joinedRooms.add(name); }
                finally { joinedLock.writeLock().unlock(); }
                updateUserRooms();
                room.broadcast(username + " joined", null);
                out.println("JOINED:" + name);
            }
        }

        void leaveRoom(String name) {
            roomsLock.readLock().lock();
            Room room = rooms.get(name);
            roomsLock.readLock().unlock();
            joinedLock.writeLock().lock();
            try {
                if (room!=null && joinedRooms.remove(name)) {
                    room.removeUser(this);
                    room.broadcast(username + " left", null);
                    out.println("LEFT:" + name);
                    updateUserRooms();
                }
            } finally { joinedLock.writeLock().unlock(); }
        }

        void sendMessage(String roomName, String msg) {
            roomsLock.readLock().lock();
            Room room = rooms.get(roomName);
            roomsLock.readLock().unlock();
            joinedLock.readLock().lock();
            try {
                if (room!=null && joinedRooms.contains(roomName)) {
                    String msgId = UUID.randomUUID().toString().substring(0,8);
                    QueuedMessage qm = new QueuedMessage(roomName, username, msg, msgId);
                    pending.put(msgId, qm);
                    room.broadcast(msg, username, msgId);
                }
            } finally { joinedLock.readLock().unlock(); }
        }

        void receiveMessage(String roomName, String sender,
                            String msg, String msgId) {
            if (active) {
                if (sender==null) out.println("SYSTEM:"+roomName+":"+msg);
                else            out.println("MESSAGE:"+roomName+":"+sender+":"+msg+":"+msgId);
            } else {
                // user disconnected—buffer
                messageBuffer.get(username)
                        .add(new QueuedMessage(roomName, sender, msg, msgId));
            }
        }

        void replayBufferedMessages() {
            Queue<QueuedMessage> buf = messageBuffer.get(username);
            QueuedMessage qm;
            while ((qm = buf.poll()) != null) {
                out.println("MESSAGE:"+
                        qm.room+":"+qm.sender+":"+qm.msg+":"+qm.msgId);
            }
        }

        private void updateUserRooms() {
            userRoomsLock.writeLock().lock();
            try {
                joinedLock.readLock().lock();
                try {
                    userRooms.put(username, new HashSet<>(joinedRooms));
                } finally {
                    joinedLock.readLock().unlock();
                }
                saveSessions();
            } finally {
                userRoomsLock.writeLock().unlock();
            }
        }

        void cleanup(boolean removeFromRooms) {
            active = false;
            if (removeFromRooms) {
                connectedLock.writeLock().lock();
                try {
                    if (connectedUsers.get(username)==this) {
                        connectedUsers.remove(username);
                    }
                } finally { connectedLock.writeLock().unlock(); }
                joinedLock.writeLock().lock();
                try {
                    for (String r : new ArrayList<>(joinedRooms)) {
                        roomsLock.readLock().lock();
                        Room room = rooms.get(r);
                        roomsLock.readLock().unlock();
                        if (room!=null) {
                            room.removeUser(this);
                            room.broadcast(username + " disconnected", null);
                        }
                    }
                    joinedRooms.clear();
                } finally { joinedLock.writeLock().unlock(); }
            }
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private class Room {
        private final String name;
        private final boolean aiRoom;
        private final String aiPrompt;
        private final Set<ClientHandler> users = new HashSet<>();
        private final ReadWriteLock usersLock = new ReentrantReadWriteLock();

        Room(String name, boolean aiRoom, String aiPrompt) {
            this.name = name; this.aiRoom = aiRoom; this.aiPrompt = aiPrompt;
        }

        String getName() { return name; }
        boolean isAiRoom() { return aiRoom; }
        void addUser(ClientHandler ch) {
            usersLock.writeLock().lock(); try { users.add(ch); }
            finally { usersLock.writeLock().unlock(); }
        }
        void removeUser(ClientHandler ch) {
            usersLock.writeLock().lock(); try { users.remove(ch); }
            finally { usersLock.writeLock().unlock(); }
        }

        void broadcast(String msg, String sender) {
            broadcast(msg, sender, UUID.randomUUID().toString().substring(0,8));
        }

        void broadcast(String msg, String sender, String msgId) {
            usersLock.readLock().lock();
            try {
                for (ClientHandler u : users) {
                    u.receiveMessage(name, sender, msg, msgId);
                }
            } finally {
                usersLock.readLock().unlock();
            }
            if (aiRoom && sender!=null && !"Bot".equals(sender)) {
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
                body.put("prompt", aiPrompt + "\nUser: " + lastUserMsg + "\nBot:");
                body.put("stream", false);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                if (conn.getResponseCode() != 200) {
                    System.err.println("AI API error: " + conn.getResponseCode());
                    return "Sorry, AI error.";
                }

                StringBuilder resp = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) resp.append(line);
                }
                return new JSONObject(resp.toString()).getString("response").trim();
            } catch (Exception e) {
                System.err.println("AI request failed: " + e.getMessage());
                return "Sorry, AI request failed.";
            }
        }
    }
}
