import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
// Removed ConcurrentHashMap import - using manual synchronization instead
import java.util.stream.Collectors;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.json.JSONObject;

public class Server {
    private static final int    PORT            = 8888;
    private static final String USERS_FILE      = "auth.txt";
    private static final String SESSION_FILE    = "sessions.txt";
    private static final String AI_ENDPOINT     = "http://localhost:11434/api/generate";

    // Use system properties for keystore paths and passwords (more secure)
    private static final String KEYSTORE_PATH   = System.getProperty("javax.net.ssl.keyStore", "chatserver.jks");
    private static final String KEYSTORE_PASS   = System.getProperty("javax.net.ssl.keyStorePassword", "password");

    private static final int    MAX_HISTORY     = 100;
    private static final int    MAX_NAME_LEN    = 50;
    private static final int    MAX_MSG_LEN     = 1000;
    private static final long   TOKEN_EXPIRY    = 24L*60*60*1000; // 24h

    // Enhanced PBKDF2 parameters for stronger security
    private static final int    SALT_LENGTH     = 32; // Increased from 16
    private static final int    PBKDF2_ITER     = 120000; // Increased from 65536
    private static final int    KEY_LENGTH      = 256;

    // Enhanced rate-limiting parameters
    private static final int    MAX_LOGIN_FAILS = 3; // Reduced from 5
    private static final long   LOGIN_WINDOW_MS = 10 * 60 * 1000; // Increased to 10 min
    private static final int    MAX_CONN_PER_IP = 5; // New: limit connections per IP

    // Enhanced fault tolerance
    private static final long   MESSAGE_TIMEOUT = 30000; // 30 seconds
    private static final long   HEARTBEAT_INTERVAL = 60000; // 1 minute

    private final ReadWriteLock                   globalLock        = new ReentrantReadWriteLock();
    private       SSLContext                       sslContext;
    // Credentials: username → (saltHex, hashHex)
    private final Map<String,String>              userSalt          = new HashMap<>();
    private final Map<String,String>              userHash          = new HashMap<>();
    private final Map<String,Room>                rooms             = new HashMap<>();
    private final Map<String,Set<ClientHandler>>  connectedUsers    = new HashMap<>();
    private final Map<String,String>              userTokens        = new HashMap<>();
    private final Map<String,String>              tokenToUser       = new HashMap<>();
    private final Map<String,Long>                tokenExpires      = new HashMap<>();
    private final Map<String,Set<String>>         userRooms         = new HashMap<>();

    // Enhanced rate-limiting: IP → list of failure timestamps
    private final Map<String,List<Long>> loginFailuresByIp = Collections.synchronizedMap(new HashMap<>());
    // New: Connection limiting per IP
    private final Map<String,Integer> connectionsPerIp = Collections.synchronizedMap(new HashMap<>());

    // Enhanced fault tolerance tracking - using manual synchronization
    private final Map<String, Long> messageTimestamps = new HashMap<>();
    private final Map<String, Long> clientHeartbeats = new HashMap<>();
    private final ReadWriteLock messageTimestampsLock = new ReentrantReadWriteLock();
    private final ReadWriteLock clientHeartbeatsLock = new ReentrantReadWriteLock();

    public static void main(String[] args) {
        // Check if security properties are properly set
        validateSecurityProperties();

        Server s = new Server();
        s.setupSSLContext();
        s.loadUsers();
        s.loadSessions();
        s.createDefaultRooms();
        s.start();
    }

    private static void validateSecurityProperties() {
        String keyStore = System.getProperty("javax.net.ssl.keyStore");
        String keyStorePassword = System.getProperty("javax.net.ssl.keyStorePassword");

        if (keyStore == null || keyStorePassword == null) {
            System.err.println("Warning: SSL properties not set via system properties.");
            System.err.println("For better security, run with:");
            System.err.println("java -Djavax.net.ssl.keyStore=chatserver.jks -Djavax.net.ssl.keyStorePassword=yourpassword Server");
        }
    }

    private void setupSSLContext() {
        String keystorePath = System.getProperty("javax.net.ssl.keyStore", KEYSTORE_PATH);
        String keystorePass = System.getProperty("javax.net.ssl.keyStorePassword", KEYSTORE_PASS);

        File ksFile = new File(keystorePath);
        if (!ksFile.exists()) {
            System.err.println("Keystore not found: " + keystorePath);
            System.err.println("Make sure to create the keystore with proper certificate validity period.");
            System.exit(1);
        }

        try {
            KeyStore ks = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(keystorePath)) {
                ks.load(fis, keystorePass.toCharArray());
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, keystorePass.toCharArray());

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);

            // Use TLS 1.3 preferentially
            sslContext = SSLContext.getInstance("TLSv1.3");

            // Use secure random
            SecureRandom secureRandom = SecureRandom.getInstanceStrong();
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), secureRandom);

            System.out.println("SSL Context initialized");
        } catch (Exception e) {
            System.err.println("SSLContext setup failed: " + e.getMessage());
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
                    // Format: username:saltHex:hashHex
                    String[] parts = line.split(":", 3);
                    if (parts.length == 3) {
                        // Validate loaded data
                        if (isValidUsername(parts[0]) &&
                                parts[1].length() == SALT_LENGTH * 2 &&
                                parts[2].length() == KEY_LENGTH / 4) {
                            userSalt.put(parts[0], parts[1]);
                            userHash.put(parts[0], parts[2]);
                        }
                    }
                }
                System.out.println("Loaded " + userSalt.size() + " users");
            }
        } catch (IOException e) {
            System.err.println("Error loading users: " + e.getMessage());
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    private void saveUsers() {
        globalLock.readLock().lock();
        try {
            List<String> lines = new ArrayList<>();
            for (String u : userSalt.keySet()) {
                lines.add(u + ":" + userSalt.get(u) + ":" + userHash.get(u));
            }
            // Write atomically
            Path tempFile = Paths.get(USERS_FILE + ".tmp");
            Files.write(tempFile, lines, StandardCharsets.UTF_8);
            Files.move(tempFile, Paths.get(USERS_FILE));
        } catch (IOException e) {
            System.err.println("Error saving users: " + e.getMessage());
        } finally {
            globalLock.readLock().unlock();
        }
    }

    private void loadSessions() {
        globalLock.writeLock().lock();
        try {
            Path p = Paths.get(SESSION_FILE);
            if (Files.exists(p)) {
                long now = System.currentTimeMillis();
                for (String line : Files.readAllLines(p)) {
                    String[] parts = line.split(":", 4);
                    if (parts.length >= 3) {
                        String user = parts[0], token = parts[1];
                        try {
                            long exp = Long.parseLong(parts[2]);
                            if (exp < now) continue; // Skip expired sessions

                            userTokens.put(user, token);
                            tokenToUser.put(token, user);
                            tokenExpires.put(token, exp);
                            Set<String> rms = (parts.length == 4 && !parts[3].isEmpty())
                                    ? new HashSet<>(Arrays.asList(parts[3].split(",")))
                                    : new HashSet<>();
                            userRooms.put(user, rms);
                        } catch (NumberFormatException e) {
                            System.err.println("Invalid session data for user: " + user);
                        }
                    }
                }
                System.out.println("Loaded " + userTokens.size() + " valid sessions");
            }
        } catch (IOException e) {
            System.err.println("Error loading sessions: " + e.getMessage());
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    private void saveSessions() {
        globalLock.readLock().lock();
        try {
            List<String> lines = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (var entry : userTokens.entrySet()) {
                String u = entry.getKey();
                String t = entry.getValue();
                Long exp = tokenExpires.get(t);
                if (exp == null || exp < now) continue; // Skip expired

                StringBuilder sb = new StringBuilder()
                        .append(u).append(":").append(t).append(":").append(exp);
                Set<String> rms = userRooms.get(u);
                if (rms != null && !rms.isEmpty()) {
                    sb.append(":").append(String.join(",", rms));
                }
                lines.add(sb.toString());
            }
            // Write atomically
            Path tempFile = Paths.get(SESSION_FILE + ".tmp");
            Files.write(tempFile, lines, StandardCharsets.UTF_8);
            Files.move(tempFile, Paths.get(SESSION_FILE));
        } catch (IOException e) {
            System.err.println("Error saving sessions: " + e.getMessage());
        } finally {
            globalLock.readLock().unlock();
        }
    }

    private void createDefaultRooms() {
        globalLock.writeLock().lock();
        try {
            rooms.put("General",      new Room("General",      false, null));
            rooms.put("Random",       new Room("Random",       false, null));
            rooms.put("AI-Assistant", new Room("AI-Assistant", true,
                    "You are a helpful AI assistant in a chat room. Be concise and helpful."));
            System.out.println("Default rooms created");
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    private void start() {
        try {
            SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
            SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(PORT);

            // Enhanced security configuration
            serverSocket.setEnabledProtocols(new String[]{"TLSv1.3","TLSv1.2"});

            // Use only strong cipher suites
            String[] strongCiphers = {
                    // TLS 1.3 ciphers
                    "TLS_AES_256_GCM_SHA384",
                    "TLS_CHACHA20_POLY1305_SHA256",
                    "TLS_AES_128_GCM_SHA256",
                    // TLS 1.2 ciphers
                    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                    "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                    "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
                    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
            };
            serverSocket.setEnabledCipherSuites(strongCiphers);

            // Server doesn't need client authentication for this chat application
            serverSocket.setNeedClientAuth(false);
            serverSocket.setWantClientAuth(false);

            System.out.println("Server listening on port " + PORT);

            Thread.startVirtualThread(this::sessionCleanupTask);
            Thread.startVirtualThread(this::connectionCleanupTask);
            Thread.startVirtualThread(this::heartbeatMonitorTask);
            Thread.startVirtualThread(this::messageTimeoutTask);

            while (true) {
                try {
                    Socket client = serverSocket.accept();
                    String clientIp = client.getInetAddress().getHostAddress();

                    // Check connection limits per IP
                    synchronized (connectionsPerIp) {
                        int current = connectionsPerIp.getOrDefault(clientIp, 0);
                        if (current >= MAX_CONN_PER_IP) {
                            System.err.println("Connection limit exceeded for IP: " + clientIp);
                            client.close();
                            continue;
                        }
                        connectionsPerIp.put(clientIp, current + 1);
                    }

                    Thread.startVirtualThread(() -> handleClient(client, clientIp));
                } catch (IOException e) {
                    System.err.println("Error accepting client: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sessionCleanupTask() {
        while (true) {
            try {
                Thread.sleep(3_600_000); // 1h
                cleanupExpiredSessions();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void connectionCleanupTask() {
        while (true) {
            try {
                Thread.sleep(300_000); // 5 min
                cleanupOldFailures();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void heartbeatMonitorTask() {
        while (true) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL);
                long now = System.currentTimeMillis();

                // Check for stale connections with proper synchronization
                clientHeartbeatsLock.writeLock().lock();
                try {
                    Iterator<Map.Entry<String, Long>> it = clientHeartbeats.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, Long> entry = it.next();
                        if (now - entry.getValue() > HEARTBEAT_INTERVAL * 2) {
                            String user = entry.getKey();
                            System.out.println("Client " + user + " appears disconnected (no heartbeat)");
                            it.remove();

                            // Cleanup user connections
                            globalLock.writeLock().lock();
                            try {
                                Set<ClientHandler> handlers = connectedUsers.get(user);
                                if (handlers != null) {
                                    for (ClientHandler handler : new ArrayList<>(handlers)) {
                                        handler.cleanup(false); // Don't remove from rooms for reconnection
                                    }
                                }
                            } finally {
                                globalLock.writeLock().unlock();
                            }
                        }
                    }
                } finally {
                    clientHeartbeatsLock.writeLock().unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void messageTimeoutTask() {
        while (true) {
            try {
                Thread.sleep(10000); // Check every 10 seconds
                long now = System.currentTimeMillis();

                messageTimestampsLock.writeLock().lock();
                try {
                    Iterator<Map.Entry<String, Long>> it = messageTimestamps.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, Long> entry = it.next();
                        if (now - entry.getValue() > MESSAGE_TIMEOUT) {
                            String msgId = entry.getKey();
                            System.out.println("Message " + msgId + " timed out without acknowledgment");
                            it.remove();
                        }
                    }
                } finally {
                    messageTimestampsLock.writeLock().unlock();
                }
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

    private void cleanupOldFailures() {
        long cutoff = System.currentTimeMillis() - LOGIN_WINDOW_MS;
        synchronized (loginFailuresByIp) {
            loginFailuresByIp.entrySet().removeIf(entry -> {
                entry.getValue().removeIf(timestamp -> timestamp < cutoff);
                return entry.getValue().isEmpty();
            });
        }
    }

    private void handleClient(Socket sock, String clientIp) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(
                     new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true)) {

            out.println("Welcome to Secure Chat Server! Please login or provide a session token.");
            String line, user = null;
            ClientHandler handler = null;

            // Authentication phase with enhanced security
            while ((line = in.readLine()) != null) {
                if (line.startsWith("LOGIN:")) {
                    // Enhanced rate-limiting by IP
                    long now = System.currentTimeMillis();
                    synchronized (loginFailuresByIp) {
                        var fails = loginFailuresByIp.computeIfAbsent(clientIp, k -> new ArrayList<>());
                        fails.removeIf(ts -> ts < now - LOGIN_WINDOW_MS);
                        if (fails.size() >= MAX_LOGIN_FAILS) {
                            out.println("AUTH_FAIL:Too many login attempts from your IP; please try later");
                            recordFailure(clientIp, now);
                            continue;
                        }
                    }

                    String[] parts = line.substring(6).split(":", 2);
                    if (parts.length != 2) {
                        out.println("AUTH_FAIL:Invalid login format");
                        recordFailure(clientIp, now);
                        continue;
                    }

                    String u = sanitizeInput(parts[0], MAX_NAME_LEN);
                    String p = parts[1];

                    // Enhanced input validation
                    if (!isValidUsername(u)) {
                        out.println("AUTH_FAIL:Invalid username format (3-50 chars, alphanumeric/_/- only)");
                        recordFailure(clientIp, now);
                        continue;
                    }

                    if (!isValidPassword(p)) {
                        out.println("AUTH_FAIL:Password must be 8+ characters with uppercase, lowercase, and digit");
                        recordFailure(clientIp, now);
                        continue;
                    }

                    globalLock.writeLock().lock();
                    try {
                        boolean isNew = !userSalt.containsKey(u);
                        if (isNew) {
                            // Register with enhanced PBKDF2
                            byte[] salt = new byte[SALT_LENGTH];
                            SecureRandom.getInstanceStrong().nextBytes(salt);
                            byte[] hash = pbkdf2(p.toCharArray(), salt);
                            String saltHex = toHex(salt), hashHex = toHex(hash);

                            userSalt.put(u, saltHex);
                            userHash.put(u, hashHex);
                            saveUsers();

                            user = u;
                            String tok = createUserToken(u);
                            out.println("AUTH_NEW:" + tok);
                            userRooms.put(u, new HashSet<>());
                            sendRoomList(out);
                            sendUserList(out);
                            System.out.println("New user registered: " + u);
                            break;
                        } else {
                            // Verify password with enhanced security
                            byte[] salt = fromHex(userSalt.get(u));
                            byte[] expected = fromHex(userHash.get(u));
                            byte[] actual = pbkdf2(p.toCharArray(), salt);

                            if (!MessageDigest.isEqual(expected, actual)) {
                                out.println("AUTH_FAIL:Invalid credentials");
                                recordFailure(clientIp, now);
                                System.out.println("Failed login attempt for " + u + " from " + clientIp);
                            } else {
                                user = u;
                                String tok = createUserToken(u);
                                out.println("AUTH_OK:" + tok);
                                sendRoomList(out);
                                sendUserList(out);
                                System.out.println("User logged in: " + u);
                                break;
                            }
                        }
                    } finally {
                        globalLock.writeLock().unlock();
                    }

                } else if (line.startsWith("TOKEN:")) {
                    String tok = line.substring(6);
                    String u = validateToken(tok);
                    if (u != null) {
                        user = u;
                        out.println("SESSION_RESUMED:" + u);
                        System.out.println("Session resumed for " + u);

                        globalLock.writeLock().lock();
                        try {
                            sendRoomList(out);
                            sendUserList(out);
                            handler = new ClientHandler(u, sock, in, out, clientIp);
                            connectedUsers.computeIfAbsent(u, k -> new HashSet<>()).add(handler);

                            // Rejoin saved rooms without history replay
                            for (String rn : userRooms.getOrDefault(u, Set.of())) {
                                Room r = rooms.get(rn);
                                if (r != null) {
                                    handler.silentlyJoinRoom(r);
                                    out.println("REJOINED:" + rn);
                                    // NO HISTORY REPLAY - just send welcome message
                                    out.println("SYSTEM:" + rn + ":Welcome back to " + rn);
                                }
                            }

                            // Update heartbeat with proper synchronization
                            clientHeartbeatsLock.writeLock().lock();
                            try {
                                clientHeartbeats.put(u, System.currentTimeMillis());
                            } finally {
                                clientHeartbeatsLock.writeLock().unlock();
                            }
                        } finally {
                            globalLock.writeLock().unlock();
                        }
                        break;
                    } else {
                        out.println("AUTH_FAIL:Invalid or expired token");
                        System.out.println("Invalid token attempt from " + clientIp);
                    }
                } else {
                    out.println("AUTH_FAIL:Unknown authentication method");
                    break;
                }
            }

            if (user == null) {
                sock.close();
                return;
            }

            if (handler == null) {
                globalLock.writeLock().lock();
                try {
                    handler = new ClientHandler(user, sock, in, out, clientIp);
                    connectedUsers.computeIfAbsent(user, k -> new HashSet<>()).add(handler);
                    clientHeartbeatsLock.writeLock().lock();
                    try {
                        clientHeartbeats.put(user, System.currentTimeMillis());
                    } finally {
                        clientHeartbeatsLock.writeLock().unlock();
                    }
                } finally {
                    globalLock.writeLock().unlock();
                }
            }

            // Main message loop
            while ((line = in.readLine()) != null) {
                if ("LOGOUT".equals(line)) {
                    removeSessionForUser(user);
                    out.println("BYE");
                    System.out.println("User logged out: " + user);
                    break;
                } else if (line.startsWith("HEARTBEAT")) {
                    clientHeartbeatsLock.writeLock().lock();
                    try {
                        clientHeartbeats.put(user, System.currentTimeMillis());
                    } finally {
                        clientHeartbeatsLock.writeLock().unlock();
                    }
                    if (line.equals("HEARTBEAT")) {
                        out.println("HEARTBEAT_ACK");
                    }
                } else {
                    dispatch(line, handler);
                }
            }
            handler.cleanup(true);

        } catch (IOException | NoSuchAlgorithmException e) {
            System.err.println("Client error from " + clientIp + ": " + e.getMessage());
        } finally {
            // Cleanup connection count
            synchronized (connectionsPerIp) {
                Integer count = connectionsPerIp.get(clientIp);
                if (count != null) {
                    if (count <= 1) {
                        connectionsPerIp.remove(clientIp);
                    } else {
                        connectionsPerIp.put(clientIp, count - 1);
                    }
                }
            }
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

    private void sendUserList(PrintWriter out) {
        globalLock.readLock().lock();
        try {
            Set<String> onlineUsers = new HashSet<>();
            for (String user : connectedUsers.keySet()) {
                if (!connectedUsers.get(user).isEmpty()) {
                    onlineUsers.add(user);
                }
            }
            out.println("USERS:" + String.join(",", onlineUsers));
        } finally {
            globalLock.readLock().unlock();
        }
    }

    private void dispatch(String line, ClientHandler h) {
        try {
            if (line.startsWith("JOIN:")) {
                String arg = line.substring(5);
                if (arg.startsWith("AI:")) {
                    String[] p = arg.split(":", 3);
                    if (p.length >= 2) {
                        String rn = sanitizeInput(p[1], MAX_NAME_LEN);
                        if (isValidRoomName(rn)) {
                            h.createOrJoin(rn, true, p.length == 3 ? p[2] : null);
                        }
                    }
                } else {
                    String rn = sanitizeInput(arg, MAX_NAME_LEN);
                    if (isValidRoomName(rn)) h.createOrJoin(rn, false, null);
                }
            } else if (line.startsWith("REJOIN:")) {
                String rn = sanitizeInput(line.substring(7), MAX_NAME_LEN);
                if (isValidRoomName(rn)) {
                    h.rejoinRoom(rn);
                }
            } else if (line.startsWith("LEAVE:")) {
                h.leaveRoom(line.substring(6));
            } else if (line.startsWith("MESSAGE:")) {
                String[] p = line.substring(8).split(":", 2);
                if (p.length == 2) {
                    String msg = sanitizeInput(p[1], MAX_MSG_LEN);
                    h.sendMessage(p[0], msg);
                }
            } else if (line.startsWith("ACK:")) {
                h.handleAck(line.substring(4));
            } else if (line.startsWith("LIST_USERS:")) {
                String roomName = line.substring(11);
                h.listUsersInRoom(roomName);
            } else if (line.startsWith("GET_HISTORY:")) {
                String roomName = line.substring(12);
                h.sendRoomHistory(roomName);
            }
        } catch (Exception e) {
            System.err.println("Error dispatching command from " + h.clientIp + ": " + e.getMessage());
        }
    }

    // --- Enhanced Session/token management ---
    private String validateToken(String t) {
        if (t == null || t.length() < 20) return null; // Basic validation
        globalLock.readLock().lock();
        try {
            String u = tokenToUser.get(t);
            Long exp = tokenExpires.get(t);
            if (u == null || exp == null || exp < System.currentTimeMillis()) return null;
            return u;
        } finally {
            globalLock.readLock().unlock();
        }
    }

    private String createUserToken(String u) {
        globalLock.writeLock().lock();
        try {
            byte[] b = new byte[48]; // Increased token size
            SecureRandom.getInstanceStrong().nextBytes(b);
            String t = Base64.getEncoder().encodeToString(b);
            long exp = System.currentTimeMillis() + TOKEN_EXPIRY;
            userTokens.put(u, t);
            tokenToUser.put(t, u);
            tokenExpires.put(t, exp);
            saveSessions();
            return t;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SecureRandom not available", e);
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    private void removeSessionForUser(String u) {
        globalLock.writeLock().lock();
        try {
            String t = userTokens.remove(u);
            if (t != null) {
                tokenToUser.remove(t);
                tokenExpires.remove(t);
            }
            clientHeartbeatsLock.writeLock().lock();
            try {
                clientHeartbeats.remove(u);
            } finally {
                clientHeartbeatsLock.writeLock().unlock();
            }
            saveSessions();
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    // --- Enhanced PBKDF2 helpers ---
    private static byte[] pbkdf2(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITER, KEY_LENGTH);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] result = skf.generateSecret(spec).getEncoded();
            spec.clearPassword(); // Clear password from memory
            return result;
        } catch (Exception e) {
            throw new RuntimeException("PBKDF2 failed", e);
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        if (hex.length() % 2 != 0) throw new IllegalArgumentException("Invalid hex string");
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }

    // --- Rate-limiting helper ---
    private void recordFailure(String ip, long timestamp) {
        synchronized (loginFailuresByIp) {
            loginFailuresByIp.computeIfAbsent(ip, k -> new ArrayList<>()).add(timestamp);
        }
    }

    // --- Enhanced validation & sanitization ---
    private boolean isValidUsername(String u) {
        if (u == null) return false;
        String clean = u.replaceAll("[^a-zA-Z0-9_-]", "").trim();
        return clean.length() >= 3 && clean.length() <= MAX_NAME_LEN && clean.equals(u.trim());
    }

    private boolean isValidPassword(String p) {
        if (p == null || p.length() < 8 || p.length() > 128) {
            return false;
        }

        return p.matches(".*[A-Z].*") && // Uppercase
                p.matches(".*[a-z].*") && // Lowercase
                p.matches(".*\\d.*") &&   // Digit
                !p.contains(":") &&       // No protocol interference
                p.trim().equals(p);       // No leading/trailing spaces
    }

    private boolean isValidRoomName(String rn) {
        if (rn == null) return false;
        String c = rn.replaceAll("[^a-zA-Z0-9_-]", "").trim();
        return c.length() >= 1 && c.length() <= MAX_NAME_LEN && c.equals(rn.trim());
    }

    private String sanitizeInput(String s, int max) {
        if (s == null) return "";
        String cleaned = s.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "").trim();
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    // --- Nested classes ---
    private static class QueuedMessage {
        final String room, sender, msg, msgId;
        final long timestamp;

        QueuedMessage(String r, String s, String m, String id) {
            room = r; sender = s; msg = m; msgId = id;
            timestamp = System.currentTimeMillis();
        }
    }

    private class ClientHandler {
        final String username;
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;
        final String clientIp;
        final Set<String> joined = new HashSet<>();
        volatile boolean active = true;
        final Map<String,QueuedMessage> pending = new HashMap<>();

        ClientHandler(String user, Socket sock, BufferedReader r, PrintWriter w, String ip) {
            username = user; socket = sock; in = r; out = w; clientIp = ip;
        }

        void handleAck(String id) {
            globalLock.writeLock().lock();
            try {
                pending.remove(id);
                messageTimestampsLock.writeLock().lock();
                try {
                    messageTimestamps.remove(id);
                } finally {
                    messageTimestampsLock.writeLock().unlock();
                }
            }
            finally {
                globalLock.writeLock().unlock();
            }
        }

        void silentlyJoinRoom(Room room) {
            room.addUser(this);
            joined.add(room.getName());
            updateUserRooms();
        }

        void createOrJoin(String name, boolean isAI, String prompt) {
            globalLock.writeLock().lock();
            try {
                boolean created = false;
                if (!rooms.containsKey(name)) {
                    String aiPrompt = prompt == null || prompt.isBlank()
                            ? "You are a helpful assistant named Bot in a chat room. Be concise and helpful."
                            : sanitizeInput(prompt, 500); // Limit prompt length
                    rooms.put(name, new Room(name, isAI, aiPrompt));
                    created = true;
                    System.out.println("Room created: " + name + (isAI ? " (AI)" : "") + " by " + username);
                }
                Room room = rooms.get(name);
                room.addUser(this);
                joined.add(name);
                updateUserRooms();

                if (created) {
                    out.println("CREATED:" + name);
                } else {
                    out.println("JOINED:" + name);
                }

                // NO HISTORY REPLAY - just send welcome message
                out.println("SYSTEM:" + name + ":Welcome to " + name);

                // Broadcast updated user list
                broadcastUserList();
            } finally {
                globalLock.writeLock().unlock();
            }
        }

        void rejoinRoom(String name) {
            globalLock.writeLock().lock();
            try {
                Room room = rooms.get(name);
                if (room != null) {
                    room.addUser(this);
                    joined.add(name);
                    updateUserRooms();
                    out.println("REJOINED:" + name);
                    // NO HISTORY REPLAY - just send welcome back message
                    out.println("SYSTEM:" + name + ":Welcome back to " + name);
                }
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
                    broadcastUserList();
                    System.out.println(username + " left room: " + name);
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
                    String id = UUID.randomUUID().toString().substring(0, 8);
                    QueuedMessage qm = new QueuedMessage(roomName, username, msg, id);
                    pending.put(id, qm);
                    messageTimestampsLock.writeLock().lock();
                    try {
                        messageTimestamps.put(id, System.currentTimeMillis());
                    } finally {
                        messageTimestampsLock.writeLock().unlock();
                    }
                    room.broadcast(msg, username, id);
                }
            } finally {
                globalLock.writeLock().unlock();
            }
        }

        void listUsersInRoom(String roomName) {
            globalLock.readLock().lock();
            try {
                Room room = rooms.get(roomName);
                if (room != null) {
                    Set<String> roomUsers = room.getUsers().stream()
                            .map(handler -> handler.username)
                            .collect(Collectors.toSet());
                    out.println("USERS:" + String.join(",", roomUsers));
                } else {
                    out.println("USERS:");
                }
            } finally {
                globalLock.readLock().unlock();
            }
        }

        void sendRoomHistory(String roomName) {
            globalLock.readLock().lock();
            try {
                Room room = rooms.get(roomName);
                if (room != null && joined.contains(roomName)) {
                    String history = room.getHistoryAsString();
                    out.println("HISTORY:" + history);
                } else {
                    out.println("HISTORY:");
                }
            } finally {
                globalLock.readLock().unlock();
            }
        }

        void receiveMessage(String roomName, String sender, String msg, String msgId) {
            if (active) {
                if (sender == null)
                    out.println("SYSTEM:" + roomName + ":" + msg);
                else
                    out.println("MESSAGE:" + roomName + ":" + sender + ":" + msg + ":" + msgId);
            }
            // REMOVED: Message buffering for offline users
        }

        private void updateUserRooms() {
            userRooms.put(username, new HashSet<>(joined));
            saveSessions();
        }

        private void broadcastUserList() {
            globalLock.readLock().lock();
            try {
                Set<String> onlineUsers = new HashSet<>();
                for (String user : connectedUsers.keySet()) {
                    if (!connectedUsers.get(user).isEmpty()) {
                        onlineUsers.add(user);
                    }
                }
                String userList = "USERS:" + String.join(",", onlineUsers);

                // Broadcast to all connected users
                for (Set<ClientHandler> handlers : connectedUsers.values()) {
                    for (ClientHandler handler : handlers) {
                        if (handler.active) {
                            handler.out.println(userList);
                        }
                    }
                }
            } finally {
                globalLock.readLock().unlock();
            }
        }

        void cleanup(boolean removeFromRooms) {
            active = false;
            globalLock.writeLock().lock();
            try {
                Set<ClientHandler> set = connectedUsers.get(username);
                if (set != null) {
                    set.remove(this);
                    if (set.isEmpty()) {
                        connectedUsers.remove(username);
                        clientHeartbeats.remove(username);
                    }
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
                    broadcastUserList();
                }
            } finally {
                globalLock.writeLock().unlock();
            }
            try {
                socket.close();
            } catch (IOException ignored) {}
            System.out.println("Connection closed for " + username);
        }
    }

    private class Room {
        private final String name;
        private final boolean aiRoom;
        private final String aiPrompt;
        private final Set<ClientHandler> users = new HashSet<>();
        private final List<QueuedMessage> history = new ArrayList<>();

        Room(String name, boolean aiRoom, String aiPrompt) {
            this.name    = name;
            this.aiRoom  = aiRoom;
            this.aiPrompt= aiPrompt;
        }

        String getName() { return name; }
        boolean isAiRoom() { return aiRoom; }
        Set<ClientHandler> getUsers() { return new HashSet<>(users); }

        void addUser(ClientHandler ch) {
            users.add(ch);
        }

        void removeUser(ClientHandler ch) {
            users.remove(ch);
        }

        // REMOVED: sendHistoryTo method - no more automatic history replay

        String getHistoryAsString() {
            return history.stream()
                    .map(qm -> {
                        if (qm.sender == null)
                            return "[SYSTEM] " + qm.msg;
                        else
                            return qm.sender + ": " + qm.msg;
                    })
                    .collect(Collectors.joining("|"));
        }

        void broadcast(String msg, String sender) {
            broadcast(msg, sender, UUID.randomUUID().toString().substring(0, 8));
        }

        void broadcast(String msg, String sender, String msgId) {
            QueuedMessage qm = new QueuedMessage(name, sender, msg, msgId);
            history.add(qm);
            if (history.size() > MAX_HISTORY) history.remove(0);

            System.out.println("DEBUG: Broadcasting message in room " + name + " from " + sender + ": " + msg);

            for (ClientHandler u : users) {
                u.receiveMessage(name, sender, msg, msgId);
            }

            // Enhanced AI response with better error handling
            if (aiRoom && sender != null && !"Bot".equals(sender)) {
                System.out.println("DEBUG: AI room detected, triggering AI response...");
                Thread.startVirtualThread(() -> {
                    try {
                        String aiReply = getAIResponse(msg);
                        if (aiReply != null && !aiReply.isEmpty()) {
                            System.out.println("DEBUG: AI replied: " + aiReply);
                            broadcast(aiReply, "Bot");
                        } else {
                            System.out.println("DEBUG: AI returned empty response");
                        }
                    } catch (Exception e) {
                        System.err.println("AI response error in room " + name + ": " + e.getMessage());
                        e.printStackTrace();
                        broadcast("Sorry, I'm experiencing technical difficulties right now.", "Bot");
                    }
                });
            } else {
                System.out.println("DEBUG: Not triggering AI - aiRoom: " + aiRoom + ", sender: " + sender);
            }
        }

        private String getAIResponse(String lastUserMsg) {
            try {
                System.out.println("DEBUG: Attempting AI response for: " + lastUserMsg);

                URL url = new URL(AI_ENDPOINT);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "SecureChatServer/1.0");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(15000);

                // Enhanced security headers
                conn.setRequestProperty("Accept", "application/json");

                JSONObject body = new JSONObject();
                body.put("model", "llama3.2:1b");

                // Sanitize and limit the prompt
                String sanitizedMsg = sanitizeInput(lastUserMsg, 500);
                String fullPrompt = aiPrompt + "\nUser: " + sanitizedMsg + "\nBot:";
                body.put("prompt", fullPrompt);
                body.put("stream", false);
                body.put("options", new JSONObject().put("temperature", 0.7));

                System.out.println("DEBUG: Sending request to AI service...");

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int responseCode = conn.getResponseCode();
                System.out.println("DEBUG: AI service response code: " + responseCode);

                if (responseCode != 200) {
                    System.err.println("AI API error: HTTP " + responseCode);
                    return "Sorry, AI service is temporarily unavailable.";
                }

                StringBuilder resp = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) resp.append(line);
                }

                System.out.println("DEBUG: Raw AI response: " + resp.toString());

                JSONObject jsonResp = new JSONObject(resp.toString());
                String response = jsonResp.getString("response").trim();

                // Sanitize AI response
                response = sanitizeInput(response, MAX_MSG_LEN);
                String finalResponse = response.isEmpty() ? "I'm not sure how to respond to that." : response;

                System.out.println("DEBUG: Final AI response: " + finalResponse);
                return finalResponse;

            } catch (java.net.ConnectException e) {
                System.err.println("AI service connection refused for room " + name + " - is Ollama running?");
                return "Sorry, AI service is currently unavailable. Please make sure Ollama is running.";
            } catch (java.net.SocketTimeoutException e) {
                System.err.println("AI service timeout for room " + name);
                return "Sorry, AI service is taking too long to respond.";
            } catch (Exception e) {
                System.err.println("AI request failed for room " + name + ": " + e.getMessage());
                e.printStackTrace(); // More detailed error info
                return "Sorry, I'm having trouble connecting to my AI service right now.";
            }
        }
    }
}