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
    private static final String USERS_FILE     = "auth.txt";
    private static final String SESSION_FILE   = "sessions.txt";
    private static final String AI_ENDPOINT    = "http://localhost:11434/api/generate";
    private static final String KEYSTORE_PATH  = "chatserver.jks";
    private static final String KEYSTORE_PASS  = "password";
    private static final int    MAX_HISTORY    = 100;
    private static final int    MAX_NAME_LEN   = 50;
    private static final int    MAX_MSG_LEN    = 1000;
    private static final long   TOKEN_EXPIRY   = 24L*60*60*1000;

    private final ReadWriteLock globalLock      = new ReentrantReadWriteLock();
    private        SSLContext     sslContext;
    private final Map<String,String>               users         = new HashMap<>();
    private final Map<String,Room>                 rooms         = new HashMap<>();
    private final Map<String,Set<ClientHandler>>   connectedUsers= new HashMap<>();
    private final Map<String,String>               userTokens    = new HashMap<>();
    private final Map<String,String>               tokenToUser   = new HashMap<>();
    private final Map<String,Long>                 tokenExpires  = new HashMap<>();
    private final Map<String,Set<String>>          userRooms     = new HashMap<>();
    private final Map<String,List<QueuedMessage>>  messageBuffer = new HashMap<>();

    public static void main(String[] args) {
        Server s = new Server();
        s.setupSSLContext();
        s.loadUsers();
        s.loadSessions();
        s.createDefaultRooms();
        s.start();
    }

    private void setupSSLContext() {
        File ksFile = new File(KEYSTORE_PATH);
        if (!ksFile.exists()) {
            System.err.println("Keystore not found: " + KEYSTORE_PATH);
            System.exit(1);
        }
        try {
            KeyStore ks = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(KEYSTORE_PATH)) {
                ks.load(fis, KEYSTORE_PASS.toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, KEYSTORE_PASS.toCharArray());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);

            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        } catch (Exception e) {
            System.err.println("SSLContext setup failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private void loadUsers() {
        globalLock.writeLock().lock();
        try {
            Path p = Paths.get(USERS_FILE);
            if (Files.exists(p)) {
                for (String line : Files.readAllLines(p)) {
                    String[] parts = line.split(":",2);
                    if (parts.length==2) users.put(parts[0], parts[1]);
                }
                System.out.println("Loaded " + users.size() + " users.");
            }
        } catch (IOException e) {
            System.err.println("Error loading users: " + e.getMessage());
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    private void saveUsers() {
        try {
            List<String> lines = new ArrayList<>();
            users.forEach((u,p)-> lines.add(u+":"+p));
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
                    String[] parts = line.split(":",4);
                    if (parts.length>=3) {
                        String user = parts[0], token = parts[1];
                        long exp = Long.parseLong(parts[2]);
                        if (exp < System.currentTimeMillis()) continue;
                        userTokens.put(user, token);
                        tokenToUser.put(token, user);
                        tokenExpires.put(token, exp);
                        Set<String> rms = parts.length==4 && !parts[3].isEmpty()
                                ? new HashSet<>(Arrays.asList(parts[3].split(",")))
                                : new HashSet<>();
                        userRooms.put(user, rms);
                        messageBuffer.put(user, new ArrayList<>());
                    }
                }
                System.out.println("Loaded " + userTokens.size() + " sessions.");
            }
        } catch (IOException e) {
            System.err.println("Error loading sessions: " + e.getMessage());
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    private void saveSessions() {
        try {
            List<String> lines = new ArrayList<>();
            for (var entry : userTokens.entrySet()) {
                String u = entry.getKey();
                String t = entry.getValue();
                Long exp = tokenExpires.get(t);
                if (exp==null || exp < System.currentTimeMillis()) continue;
                StringBuilder sb = new StringBuilder()
                        .append(u).append(":").append(t).append(":").append(exp);
                Set<String> rms = userRooms.get(u);
                if (rms != null && !rms.isEmpty()) {
                    sb.append(":").append(String.join(",", rms));
                }
                lines.add(sb.toString());
            }
            Files.write(Paths.get(SESSION_FILE), lines);
        } catch (IOException e) {
            System.err.println("Error saving sessions: " + e.getMessage());
        }
    }

    private void createDefaultRooms() {
        globalLock.writeLock().lock();
        try {
            rooms.put("General", new Room("General", false, null));
            rooms.put("Random",  new Room("Random",  false, null));
            rooms.put("AI-Assistant",
                    new Room("AI-Assistant", true,
                            "You are a helpful AI assistant in a chat room."));
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    private void start() {
        try {
            SSLServerSocketFactory factory =
                    sslContext.getServerSocketFactory();
            SSLServerSocket serverSocket =
                    (SSLServerSocket) factory.createServerSocket(PORT);
            serverSocket.setEnabledProtocols(
                    new String[]{"TLSv1.3","TLSv1.2"});

            System.out.println("Server listening on port " + PORT);
            Thread.startVirtualThread(this::sessionCleanupTask);

            while (true) {
                Socket client = serverSocket.accept();
                Thread.startVirtualThread(() -> handleClient(client));
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
        globalLock.writeLock().lock();
        try {
            List<String> expired = new ArrayList<>();
            for (var e : tokenExpires.entrySet()) {
                if (e.getValue() < now) expired.add(e.getKey());
            }
            for (String t : expired) {
                String u = tokenToUser.remove(t);
                tokenExpires.remove(t);
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

    private void handleClient(Socket sock) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(sock.getInputStream()));
             PrintWriter out = new PrintWriter(
                     sock.getOutputStream(), true)) {

            out.println("👋 Welcome! Please login or provide a session token.");
            String line, user = null;
            ClientHandler handler = null;

            // Authentication
            while ((line = in.readLine()) != null) {
                if (line.startsWith("LOGIN:")) {
                    String[] parts = line.substring(6).split(":",2);
                    if (parts.length==2) {
                        String u = sanitizeInput(parts[0], MAX_NAME_LEN);
                        String p = parts[1];
                        if (!isValidUsername(u)) {
                            out.println("AUTH_FAIL:Invalid username format");
                            continue;
                        }
                        globalLock.writeLock().lock();
                        try {
                            boolean isNew = !users.containsKey(u);
                            if (isNew) {
                                registerUser(u,p);
                                user = u;
                                String tok = createUserToken(u);
                                out.println("AUTH_NEW:" + tok);
                                userRooms.put(u, new HashSet<>());
                                messageBuffer.put(u,new ArrayList<>());
                                sendRoomList(out);
                                break;
                            } else if (!sha256(p).equals(users.get(u))) {
                                out.println("AUTH_FAIL:Invalid credentials");
                            } else {
                                user = u;
                                String tok = createUserToken(u);
                                out.println("AUTH_OK:" + tok);
                                messageBuffer.putIfAbsent(u,new ArrayList<>());
                                sendRoomList(out);
                                break;
                            }
                        } finally {
                            globalLock.writeLock().unlock();
                        }
                    }
                } else if (line.startsWith("TOKEN:")) {
                    String tok = line.substring(6);
                    String u = validateToken(tok);
                    if (u != null) {
                        user = u;
                        out.println("SESSION_RESUMED:" + u);
                        globalLock.writeLock().lock();
                        try {
                            messageBuffer.putIfAbsent(u,new ArrayList<>());
                            sendRoomList(out);
                            handler = new ClientHandler(u,sock,in,out);
                            // add to set, don't evict old
                            connectedUsers.computeIfAbsent(u,k->new HashSet<>()).add(handler);

                            // rejoin saved rooms
                            for (String rn : userRooms.getOrDefault(u, Set.of())) {
                                Room r = rooms.get(rn);
                                if (r != null) {
                                    handler.silentlyJoinRoom(r);
                                    out.println("REJOINED:" + rn);
                                    r.sendHistoryTo(handler);
                                }
                            }
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
            if (user == null) {
                sock.close();
                return;
            }
            if (handler == null) {
                globalLock.writeLock().lock();
                try {
                    handler = new ClientHandler(user,sock,in,out);
                    connectedUsers.computeIfAbsent(user,k->new HashSet<>()).add(handler);
                } finally {
                    globalLock.writeLock().unlock();
                }
            }

            // Main loop
            while ((line = in.readLine()) != null) {
                if ("LOGOUT".equals(line)) {
                    removeSessionForUser(user);
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
        StringBuilder sb = new StringBuilder("ROOMS:");
        for (Room r : rooms.values()) {
            sb.append(r.getName());
            if (r.isAiRoom()) sb.append(":AI");
            sb.append(",");
        }
        out.println(sb);
    }

    private void dispatch(String line, ClientHandler h) {
        if (line.startsWith("JOIN:")) {
            String arg = line.substring(5);
            if (arg.startsWith("AI:")) {
                String[] p = arg.split(":",3);
                if (p.length>=2) {
                    String rn = sanitizeInput(p[1], MAX_NAME_LEN);
                    if (isValidRoomName(rn)) {
                        h.createOrJoin(rn,true,p.length==3?p[2]:null);
                    }
                }
            } else {
                String rn = sanitizeInput(arg, MAX_NAME_LEN);
                if (isValidRoomName(rn)) h.createOrJoin(rn,false,null);
            }
        } else if (line.startsWith("LEAVE:")) {
            h.leaveRoom(line.substring(6));
        } else if (line.startsWith("MESSAGE:")) {
            String[] p = line.substring(8).split(":",2);
            if (p.length==2) {
                String msg = sanitizeInput(p[1], MAX_MSG_LEN);
                h.sendMessage(p[0], msg);
            }
        } else if (line.startsWith("ACK:")) {
            h.handleAck(line.substring(4));
        }
    }

    // Session/token management...
    private String validateToken(String t) {
        if (t==null) return null;
        globalLock.readLock().lock();
        try {
            String u = tokenToUser.get(t);
            Long exp = tokenExpires.get(t);
            if (u==null || exp==null || exp < System.currentTimeMillis()) return null;
            return u;
        } finally {
            globalLock.readLock().unlock();
        }
    }
    private String createUserToken(String u) {
        globalLock.writeLock().lock();
        try {
            byte[] b = new byte[32];
            new SecureRandom().nextBytes(b);
            String t = Base64.getEncoder().encodeToString(b);
            long exp = System.currentTimeMillis() + TOKEN_EXPIRY;
            userTokens.put(u, t);
            tokenToUser.put(t, u);
            tokenExpires.put(t, exp);
            saveSessions();
            return t;
        } finally {
            globalLock.writeLock().unlock();
        }
    }
    private void removeSessionForUser(String u) {
        globalLock.writeLock().lock();
        try {
            String t = userTokens.remove(u);
            if (t!=null) {
                tokenToUser.remove(t);
                tokenExpires.remove(t);
            }
            saveSessions();
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    private boolean isValidUsername(String u) {
        if (u==null) return false;
        String clean = u.replaceAll("[^a-zA-Z0-9_-]","").trim();
        return clean.length()>=3 && clean.length()<=MAX_NAME_LEN;
    }
    private boolean isValidRoomName(String rn) {
        if (rn==null) return false;
        String c = rn.replaceAll("[^a-zA-Z0-9_-]","").trim();
        return c.length()>=1 && c.length()<=MAX_NAME_LEN;
    }
    private String sanitizeInput(String s,int max) {
        if (s==null) return "";
        String cleaned = s.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]","").trim();
        return cleaned.length()>max?cleaned.substring(0,max):cleaned;
    }
    private String sha256(String in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(in.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b: d) sb.append(String.format("%02x",b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    private boolean registerUser(String u,String p) {
        users.put(u, sha256(p));
        saveUsers();
        return true;
    }

    // Represents queued offline messages
    private static class QueuedMessage {
        final String room, sender, msg, msgId;
        final long timestamp;
        QueuedMessage(String r, String s, String m, String id) {
            room=r; sender=s; msg=m; msgId=id; timestamp=System.currentTimeMillis();
        }
    }

    private class ClientHandler {
        final String username;
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;
        final Set<String> joined = new HashSet<>();
        volatile boolean active = true;
        final Map<String,QueuedMessage> pending = new HashMap<>();

        ClientHandler(String user, Socket sock,
                      BufferedReader r, PrintWriter w) {
            username=user; socket=sock; in=r; out=w;
        }

        void handleAck(String id) {
            globalLock.writeLock().lock();
            try { pending.remove(id); }
            finally { globalLock.writeLock().unlock(); }
        }

        void silentlyJoinRoom(Room room) {
            room.addUser(this);
            joined.add(room.getName());
            updateUserRooms();
        }

        void createOrJoin(String name, boolean isAI, String prompt) {
            globalLock.writeLock().lock();
            try {
                if (!rooms.containsKey(name)) {
                    rooms.put(name, new Room(name,isAI,
                            prompt==null||prompt.isBlank()
                                    ? "You are a helpful assistant named Bot."
                                    : prompt));
                    out.println("CREATED:" + name);
                }
                Room room = rooms.get(name);
                room.addUser(this);
                joined.add(name);
                updateUserRooms();
                room.broadcast(username + " joined", null);
                out.println("JOINED:" + name);
                room.sendHistoryTo(this);
            } finally {
                globalLock.writeLock().unlock();
            }
        }

        void leaveRoom(String name) {
            globalLock.writeLock().lock();
            try {
                Room room = rooms.get(name);
                if (room != null && joined.remove(name)) {
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
                if (room != null && joined.contains(roomName)) {
                    String id = UUID.randomUUID().toString().substring(0,8);
                    QueuedMessage qm = new QueuedMessage(roomName, username, msg, id);
                    pending.put(id, qm);
                    room.broadcast(msg, username, id);
                }
            } finally {
                globalLock.writeLock().unlock();
            }
        }

        void receiveMessage(String roomName, String sender, String msg, String msgId) {
            if (active) {
                if (sender==null) out.println("SYSTEM:" + roomName + ":" + msg);
                else            out.println("MESSAGE:" + roomName + ":" + sender + ":" + msg + ":" + msgId);
            } else {
                messageBuffer.get(username).add(new QueuedMessage(roomName,sender,msg,msgId));
            }
        }

        void replayBufferedMessages() {
            List<QueuedMessage> buf = messageBuffer.get(username);
            for (QueuedMessage qm : new ArrayList<>(buf)) {
                if (qm.sender==null)
                    out.println("SYSTEM:" + qm.room + ":" + qm.msg);
                else
                    out.println("MESSAGE:" + qm.room + ":" + qm.sender + ":" + qm.msg + ":" + qm.msgId);
            }
            buf.clear();
        }

        private void updateUserRooms() {
            userRooms.put(username, new HashSet<>(joined));
            saveSessions();
        }

        void cleanup(boolean removeFromRooms) {
            active = false;
            globalLock.writeLock().lock();
            try {
                // Remove from connectedUsers
                Set<ClientHandler> set = connectedUsers.get(username);
                if (set != null) {
                    set.remove(this);
                    if (set.isEmpty()) connectedUsers.remove(username);
                }
                if (removeFromRooms) {
                    for (String r : new ArrayList<>(joined)) {
                        Room room = rooms.get(r);
                        if (room != null) {
                            room.removeUser(this);
                            room.broadcast(username + " disconnected", null);
                        }
                    }
                    joined.clear();
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
        private final List<QueuedMessage> history = new ArrayList<>();

        Room(String name, boolean aiRoom, String aiPrompt) {
            this.name = name;
            this.aiRoom = aiRoom;
            this.aiPrompt = aiPrompt;
        }
        String getName() { return name; }
        boolean isAiRoom() { return aiRoom; }

        void addUser(ClientHandler ch) {
            users.add(ch);
        }
        void removeUser(ClientHandler ch) {
            users.remove(ch);
        }
        void sendHistoryTo(ClientHandler u) {
            for (QueuedMessage qm : history) {
                if (qm.sender == null)
                    u.out.println("SYSTEM:" + qm.room + ":" + qm.msg);
                else
                    u.out.println("MESSAGE:" + qm.room + ":" + qm.sender + ":" + qm.msg + ":" + qm.msgId);
            }
        }

        void broadcast(String msg, String sender) {
            broadcast(msg, sender, UUID.randomUUID().toString().substring(0,8));
        }

        void broadcast(String msg, String sender, String msgId) {
            QueuedMessage qm = new QueuedMessage(name,sender,msg,msgId);
            history.add(qm);
            if (history.size() > MAX_HISTORY) history.remove(0);

            for (ClientHandler u : users) {
                u.receiveMessage(name, sender, msg, msgId);
            }

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
                body.put("model","llama3.2:1b");
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
                return new JSONObject(resp.toString()).getString("response").trim();
            } catch (Exception e) {
                System.err.println("AI request failed: " + e.getMessage());
                return "Sorry, I'm having trouble connecting to my AI service right now.";
            }
        }
    }
}
