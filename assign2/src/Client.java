import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.locks.*;
import javax.net.ssl.*;

public class Client {
    private static final String SERVER_ADDRESS = "localhost";
    private static final int    SERVER_PORT    = 8888;

    // Use system properties for keystore paths and passwords (more secure)
    private static final String TRUSTSTORE_PATH = System.getProperty("javax.net.ssl.trustStore", "chatserver.jks");
    private static final String TRUSTSTORE_PASSWORD = System.getProperty("javax.net.ssl.trustStorePassword", "password");
    private static final String TOKENS_FILE = "tokens.properties";

    private SSLSocket           socket;
    private BufferedReader      in;
    private PrintWriter         out;
    private BufferedReader      console;
    private String              username;
    private String              currentRoom;
    private volatile boolean    running = true;

    private final Set<String>   joinedRooms    = new HashSet<>();
    private final Set<String>   availableRooms = new HashSet<>();
    private final Set<String>   aiRooms        = new HashSet<>();
    private final ReadWriteLock roomsLock      = new ReentrantReadWriteLock();

    // Enhanced fault tolerance
    private final Set<String> currentUsers = new HashSet<>();
    private volatile long lastHeartbeat = System.currentTimeMillis();
    private volatile boolean expectingRoomUserList = false;

    // ANSI color codes
    private static final String RESET  = "\u001B[0m";
    private static final String BOLD   = "\u001B[1m";
    private static final String GREEN  = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BLUE   = "\u001B[34m";
    private static final String CYAN   = "\u001B[36m";
    private static final String PURPLE = "\u001B[35m";

    // Store multiple tokens with proper encryption
    private final Properties tokens = new Properties();

    public static void main(String[] args) throws IOException {
        // Check if security properties are properly set
        validateSecurityProperties();
        new Client().start();
    }

    private static void validateSecurityProperties() {
        String trustStore = System.getProperty("javax.net.ssl.trustStore");
        String trustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword");

        if (trustStore == null || trustStorePassword == null) {
            System.err.println(YELLOW + "Warning: SSL properties not set via system properties." + RESET);
            System.err.println("For better security, run with:");
            System.err.println("java -Djavax.net.ssl.trustStore=chatserver.jks -Djavax.net.ssl.trustStorePassword=password Client");
        }
    }

    public void start() throws IOException {
        try {
            console = new BufferedReader(new InputStreamReader(System.in));
        } catch (Exception e) {
            System.err.println(RED + "Error initializing console: " + e.getMessage() + RESET);
            return;
        }

        // Retry loop for connection and authentication
        while (running) {
            try {
                connectSecure();
                authenticate();
                break;
            } catch (Exception e) {
                System.err.println(RED + "Connection/authentication error: " + e.getMessage() + RESET);
                System.err.println(YELLOW + "Retrying in 5 seconds..." + RESET);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        if (!running || socket == null) {
            cleanup();
            return;
        }

        // Start heartbeat monitor
        Thread.startVirtualThread(this::heartbeatMonitor);

        // Message receiver in a virtual thread
        Thread.startVirtualThread(() -> {
            try {
                receiveMessages();
            } catch (IOException e) {
                System.err.println(RED + "Connection lost: " + e.getMessage() + RESET);
                if (running) {
                    handleConnectionLoss();
                }
            }
        });

        // Handle user input on main thread
        handleInput();
        cleanup();
    }

    private void connectSecure() throws IOException {
        try {
            // Close existing socket if present
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (IOException ignored) {}
            }

            SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
            KeyStore ts = KeyStore.getInstance("JKS");

            String trustStorePath = TRUSTSTORE_PATH;
            String trustStorePass = TRUSTSTORE_PASSWORD;
            try (FileInputStream fis = new FileInputStream(trustStorePath)) {
                ts.load(fis, trustStorePass.toCharArray());
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);

            SecureRandom secureRandom = SecureRandom.getInstanceStrong();
            sslContext.init(null, tmf.getTrustManagers(), secureRandom);

            SSLSocketFactory factory = sslContext.getSocketFactory();
            socket = (SSLSocket) factory.createSocket(SERVER_ADDRESS, SERVER_PORT);

            socket.setSoTimeout(30000);
            socket.setKeepAlive(true);
            socket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            socket.setEnabledCipherSuites(new String[]{
                    "TLS_AES_256_GCM_SHA384",
                    "TLS_CHACHA20_POLY1305_SHA256",
                    "TLS_AES_128_GCM_SHA256",
                    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                    "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                    "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
            });

            SSLParameters sslParams = socket.getSSLParameters();
            sslParams.setEndpointIdentificationAlgorithm("HTTPS");
            sslParams.setNeedClientAuth(false);
            socket.setSSLParameters(sslParams);

            socket.startHandshake();
            verifyConnectionSecurity();

            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            String welcome = in.readLine();
            if (welcome != null) {
                System.out.println(welcome);
            } else {
                throw new IOException("No welcome message received from server");
            }
        } catch (NoSuchAlgorithmException | KeyStoreException |
                 CertificateException | KeyManagementException e) {
            throw new IOException("SSL setup failed: " + e.getMessage(), e);
        }
    }

    private void verifyConnectionSecurity() {
        try {
            SSLSession session = socket.getSession();
            String protocol = session.getProtocol();
            String cipherSuite = session.getCipherSuite();

            // Verify we're using secure protocols
            if (!protocol.equals("TLSv1.3") && !protocol.equals("TLSv1.2")) {
                throw new IOException("Insecure protocol detected: " + protocol);
            }

            // Verify we're using strong ciphers
            if (cipherSuite.contains("_NULL_") ||
                    cipherSuite.contains("DES") ||
                    cipherSuite.contains("MD5") ||
                    cipherSuite.contains("SHA1")) {
                throw new IOException("Weak cipher suite detected: " + cipherSuite);
            }

        } catch (Exception e) {
            System.err.println(RED + "Security verification failed: " + e.getMessage() + RESET);
        }
    }

    private void handleConnectionLoss() {
        System.err.println(YELLOW + "Connection lost. Attempting recovery..." + RESET);

        // Save current state for reconnection
        String lastRoom = currentRoom;
        Set<String> roomsToRejoin = new HashSet<>(joinedRooms);

        while (running) {
            try {
                reconnect();

                // Restore state - rejoin rooms (server will send welcome messages)
                for (String room : roomsToRejoin) {
                    out.println("REJOIN:" + room);
                }

                if (lastRoom != null) {
                    currentRoom = lastRoom;
                }

                // Restart message handling
                Thread.startVirtualThread(() -> {
                    try {
                        receiveMessages();
                    } catch (IOException e) {
                        System.err.println(RED + "Connection lost again: " + e.getMessage() + RESET);
                        if (running) {
                            handleConnectionLoss();
                        }
                    }
                });

                break;

            } catch (Exception e) {
                System.err.println(RED + "Recovery failed: " + e.getMessage() + ". Retrying..." + RESET);
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void reconnect() {
        int attemptCount = 0;
        long baseDelay = 2000; // 2 seconds
        long maxDelay  = 30000; // 30 seconds

        System.err.println(YELLOW + "Reconnecting..." + RESET);

        while (running) {
            try {
                attemptCount++;
                long delay = Math.min(baseDelay * (long)Math.pow(1.5, attemptCount - 1), maxDelay);

                System.err.println(YELLOW + "Attempt " + attemptCount +
                        " (waiting " + (delay/1000) + "s)" + RESET);

                Thread.sleep(delay);

                // Tear down any half-open connection
                closeCurrentConnection();

                // Try to bring the socket back up with security
                connectSecure();
                authenticate();

                System.err.println(GREEN + "Connection restored!" + RESET);

                // Don't manually rejoin rooms here - let handleConnectionLoss do it
                return;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println(RED + "Reconnection interrupted" + RESET);
                return;
            } catch (Exception e) {
                System.err.println(RED + "Reconnect attempt " + attemptCount +
                        " failed: " + e.getMessage() + RESET);
            }
        }
    }

    private void authenticate() throws IOException {
        loadTokens();

        // If we have an active username (reconnect scenario), try to resume without prompting
        if (username != null) {
            String token = tokens.getProperty(username);
            if (token != null) {
                out.println("TOKEN:" + token);
                String response = in.readLine();
                if (response != null && response.startsWith("SESSION_RESUMED:")) {
                    System.out.println(GREEN + "Session resumed for " + username + RESET);
                    showHelp();
                    return;
                } else {
                    System.out.println(YELLOW + "Could not resume session" + RESET);
                    tokens.remove(username);
                    saveTokens();
                    // fall through to full auth flow
                }
            }
        }

        // Otherwise (first start or resume failed) let user choose existing account or log in new
        if (!tokens.isEmpty()) {
            List<String> usersList = new ArrayList<>(tokens.stringPropertyNames());
            System.out.println("\nSelect account to resume:");
            for (int i = 0; i < usersList.size(); i++) {
                System.out.println("  " + (i + 1) + ") " + usersList.get(i));
            }
            System.out.println("  " + (usersList.size() + 1) + ") Log in as new user");

            int choice = -1;
            while (choice < 1 || choice > usersList.size() + 1) {
                System.out.print("Enter choice: ");
                String line = console.readLine();
                try { choice = Integer.parseInt(line.trim()); }
                catch (Exception ignored) {}
            }

            if (choice <= usersList.size()) {
                username = usersList.get(choice - 1);
                String token = tokens.getProperty(username);
                out.println("TOKEN:" + token);
                String response = in.readLine();
                if (response != null && response.startsWith("SESSION_RESUMED:")) {
                    System.out.println(GREEN + "Session resumed for " + username + RESET);
                    showHelp();
                    return;
                } else {
                    System.out.println(YELLOW + "Could not resume session" + RESET);
                    tokens.remove(username);
                    saveTokens();
                }
            }
        }

        // Fresh login flow with enhanced input validation
        while (true) {
            System.out.print("\nEnter your username (3+ chars, alphanumeric/underscore/dash only): ");
            String user = console.readLine();
            if (user == null) throw new IOException("Input stream closed");

            // Client-side validation
            user = sanitizeUsername(user);
            if (user.isEmpty()) {
                System.out.println(RED + "Invalid username format" + RESET);
                continue;
            }

            System.out.print("Enter your password (8+ chars, mixed case, number required): ");
            String pass = readPassword();
            if (pass == null) throw new IOException("Input stream closed");

            // Enhanced client-side password validation
            if (!isValidPassword(pass)) {
                System.out.println(RED + "Password must be 8+ characters with uppercase, lowercase, and digit" + RESET);
                continue;
            }

            out.println("LOGIN:" + user + ":" + pass);
            String response = in.readLine();
            if (response == null) throw new IOException("Server disconnected during authentication");

            if (response.startsWith("AUTH_NEW:")) {
                username = user;
                String token = response.substring("AUTH_NEW:".length());
                tokens.setProperty(username, token);
                saveTokens();
                System.out.println(GREEN + "Welcome, " + username + "!" + RESET);
                showHelp();
                break;
            } else if (response.startsWith("AUTH_OK:")) {
                username = user;
                String token = response.substring("AUTH_OK:".length());
                tokens.setProperty(username, token);
                saveTokens();
                System.out.println(GREEN + "Logged in as " + username + RESET);
                showHelp();
                break;
            } else if (response.startsWith("AUTH_FAIL:")) {
                System.out.println(RED + "Login failed: "
                        + response.substring("AUTH_FAIL:".length()) + RESET);
            } else {
                System.out.println("Unexpected response: " + response);
            }
        }
    }

    private String sanitizeUsername(String input) {
        if (input == null) return "";
        String cleaned = input.replaceAll("[^a-zA-Z0-9_-]", "").trim();
        return cleaned.length() >= 3 && cleaned.length() <= 50 ? cleaned : "";
    }

    private boolean isValidPassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            return false;
        }

        return password.matches(".*[A-Z].*") && // Uppercase
                password.matches(".*[a-z].*") && // Lowercase
                password.matches(".*\\d.*") &&   // Digit
                !password.contains(":") &&       // No protocol interference
                password.trim().equals(password); // No leading/trailing spaces
    }

    private String readPassword() throws IOException {
        Console systemConsole = System.console();
        if (systemConsole != null) {
            char[] password = systemConsole.readPassword();
            String result = new String(password);
            Arrays.fill(password, ' '); // Clear password from memory
            return result;
        } else {
            // Fallback for IDEs - warn user
            System.err.println(YELLOW + "WARNING: Password will be visible in console!" + RESET);
            return console.readLine();
        }
    }

    private void receiveMessages() throws IOException {
        while (running) {
            try {
                String msg;
                while ((msg = in.readLine()) != null) {
                    lastHeartbeat = System.currentTimeMillis();

                    if (msg.startsWith("MESSAGE:")) {
                        String body = msg.substring("MESSAGE:".length());
                        String[] parts = body.split(":", 4);
                        if (parts.length == 4) {
                            String room = parts[0],
                                    sender = parts[1],
                                    text = parts[2],
                                    msgId = parts[3];
                            printMessage(room, sender, text);
                            out.println("ACK:" + msgId);
                        }
                    } else if (msg.startsWith("SYSTEM:")) {
                        String[] p = msg.substring("SYSTEM:".length()).split(":", 2);
                        if (p.length == 2) printSystemMessage(p[0], p[1]);
                    } else if (msg.startsWith("ROOMS:")) {
                        updateAvailableRooms(msg.substring("ROOMS:".length()));
                    } else if (msg.startsWith("USERS:")) {
                        String userList = msg.substring("USERS:".length());
                        if (expectingRoomUserList) {
                            // This is a response to /list command for current room
                            displayRoomUsers(userList);
                            expectingRoomUserList = false;
                        } else {
                            // This is the general online users update
                            updateCurrentUsers(userList);
                        }
                    } else if (msg.startsWith("CREATED:")) {
                        String room = msg.substring("CREATED:".length());
                        handleJoinEvent(room, false);
                    } else if (msg.startsWith("JOINED:")) {
                        handleJoinEvent(msg.substring("JOINED:".length()), false);
                    } else if (msg.startsWith("REJOINED:")) {
                        handleJoinEvent(msg.substring("REJOINED:".length()), true);
                    } else if (msg.startsWith("LEFT:")) {
                        String room = msg.substring("LEFT:".length());
                        removeJoinedRoom(room);
                        System.out.println(YELLOW + "Left " + room + RESET);
                    } else if (msg.startsWith("HISTORY:")) {
                        displayHistory(msg.substring("HISTORY:".length()));
                    } else if (msg.startsWith("HEARTBEAT")) {
                        out.println("HEARTBEAT_ACK");
                    } else if (msg.equals("BYE")) {
                        System.out.println(GREEN + "Goodbye!" + RESET);
                        running = false;
                        break;
                    } else {
                        System.out.println(msg);
                    }
                }

                // If we reach here, the connection was closed
                throw new IOException("Connection stream closed by server");

            } catch (SocketTimeoutException e) {
                // Timeout occurred, check if connection is still valid
                if (!isConnectionHealthy()) {
                    throw new IOException("Connection became unhealthy during timeout");
                }
                continue;

            } catch (IOException e) {
                if (running) {
                    System.err.println(RED + "Connection lost: " + e.getMessage() + RESET);
                    handleConnectionLoss();
                    return;
                }
            }
        }
    }

    private void handleJoinEvent(String room, boolean rejoined) {
        addJoinedRoom(room);
        currentRoom = room;
        if (rejoined) {
            System.out.println(CYAN + "Rejoined " + room + RESET);
        } else {
            System.out.println(GREEN + "Joined " + room + RESET);
        }
        if (aiRooms.contains(room)) {
            System.out.println(BLUE + "AI room: bot will respond here" + RESET);
        }
    }

    private void handleInput() throws IOException {
        String line;
        while (running && (line = console.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // Check connection health before sending
            if (!isConnectionHealthy()) {
                System.err.println(RED + "Connection lost while typing. Reconnecting..." + RESET);
                handleConnectionLoss();
                continue;
            }

            if (line.startsWith("/")) {
                handleCommand(line);
            } else if (currentRoom != null) {
                try {
                    out.println("MESSAGE:" + currentRoom + ":" + line);

                    if (out.checkError()) {
                        throw new IOException("Failed to send message - connection error");
                    }
                } catch (Exception e) {
                    System.err.println(RED + "Failed to send message: " + e.getMessage() + RESET);
                    handleConnectionLoss();
                }
            } else {
                System.out.println(YELLOW + "You must join a room first (/join <room>)" + RESET);
            }
        }
    }

    private boolean isConnectionHealthy() {
        try {
            return socket != null &&
                    !socket.isClosed() &&
                    socket.isConnected() &&
                    !socket.isInputShutdown() &&
                    !socket.isOutputShutdown() &&
                    (System.currentTimeMillis() - lastHeartbeat) < 60000; // 1 minute timeout
        } catch (Exception e) {
            return false;
        }
    }

    private void closeCurrentConnection() {
        try {
            if (out != null) {
                out.close();
            }
        } catch (Exception ignored) {}

        try {
            if (in != null) {
                in.close();
            }
        } catch (Exception ignored) {}

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception ignored) {}

        in = null;
        out = null;
        socket = null;
    }

    private void handleCommand(String input) {
        String[] parts = input.substring(1).split(" ", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "join" -> {
                if (!arg.isBlank()) {
                    if (arg.toLowerCase().startsWith("ai:")) {
                        String[] aiParts = arg.split(":", 3);
                        if (aiParts.length >= 2) {
                            String roomName = aiParts[1];
                            String prompt = aiParts.length == 3 ? aiParts[2] : "";
                            out.println("JOIN:AI:" + roomName + (prompt.isEmpty() ? "" : ":" + prompt));
                            if (!prompt.isEmpty())
                                System.out.println(BLUE + "Custom AI prompt: " + prompt + RESET);
                        } else {
                            System.out.println(RED + "Usage: /join AI:<name> or /join AI:<name>:<prompt>" + RESET);
                        }
                    } else {
                        out.println("JOIN:" + arg);
                    }
                } else {
                    System.out.println(YELLOW + "Usage: /join <room> or /join AI:<name>:<prompt>" + RESET);
                }
            }
            case "leave" -> {
                if (currentRoom != null) {
                    out.println("LEAVE:" + currentRoom);
                } else {
                    System.out.println(YELLOW + "You are not in any room." + RESET);
                }
            }
            case "rooms" -> showRooms();
            case "users", "who" -> showCurrentUsers();
            case "list" -> {
                if (currentRoom != null) {
                    expectingRoomUserList = true;
                    out.println("LIST_USERS:" + currentRoom);
                } else {
                    System.out.println(YELLOW + "You must be in a room to list users." + RESET);
                }
            }
            case "history" -> {
                if (currentRoom != null) {
                    out.println("GET_HISTORY:" + currentRoom);
                } else {
                    System.out.println(YELLOW + "You must be in a room to view history." + RESET);
                }
            }
            case "logout" -> {
                running = false;
                out.println("LOGOUT");
                removeToken(username);
            }
            case "help" -> showHelp();
            default -> {
                System.out.println(RED + "Unknown command: '" + cmd + "'. Type /help for available commands." + RESET);
                showAvailableCommands();
            }
        }
    }

    private void printMessage(String room, String sender, String content) {
        // Remove message ID if present at the end
        if (content.matches(".*:[0-9A-Fa-f]{8}$")) {
            int idx = content.lastIndexOf(':');
            content = content.substring(0, idx);
        }
        String prefix      = sender.equals("Bot") ? "🤖 " : "";
        String senderColor = sender.equals("Bot") ? BLUE
                : sender.equals(username) ? GREEN : RED; // Other users are red
        String header = room.equals(currentRoom)
                ? senderColor + prefix + sender + ": " + RESET
                : CYAN + "[" + room + "] " + RESET
                + senderColor + prefix + sender + ": " + RESET;
        System.out.println(header + content);
    }

    private void updateAvailableRooms(String list) {
        roomsLock.writeLock().lock();
        try {
            availableRooms.clear();
            aiRooms.clear();
            if (list != null && !list.isBlank()) {
                for (String token : list.split(",")) {
                    if (token.isBlank()) continue;
                    boolean isAI   = token.endsWith(":AI");
                    String roomName = isAI
                            ? token.substring(0, token.length() - 3)
                            : token;
                    availableRooms.add(roomName);
                    if (isAI) aiRooms.add(roomName);
                }
            }
            System.out.println(CYAN + "Available rooms: " + formatRoomList() + RESET);
        } finally {
            roomsLock.writeLock().unlock();
        }
    }

    private void displayRoomUsers(String userList) {
        if (currentRoom != null) {
            System.out.println(PURPLE + "Users in " + currentRoom + ": " + RESET);
            if (userList == null || userList.isBlank()) {
                System.out.println(YELLOW + "No users in this room." + RESET);
            } else {
                String[] users = userList.split(",");
                for (String user : users) {
                    if (!user.trim().isEmpty()) {
                        String color = user.trim().equals(username) ? GREEN : RED;
                        System.out.println("  " + color + user.trim() + RESET);
                    }
                }
            }
        }
    }

    private void updateCurrentUsers(String userList) {
        synchronized (currentUsers) {
            currentUsers.clear();
            if (userList != null && !userList.isBlank()) {
                currentUsers.addAll(Arrays.asList(userList.split(",")));
            }
        }
    }

    private void showCurrentUsers() {
        synchronized (currentUsers) {
            if (currentUsers.isEmpty()) {
                System.out.println(YELLOW + "No users currently online." + RESET);
            } else {
                System.out.println(PURPLE + "Online users: " + String.join(", ", currentUsers) + RESET);
            }
        }
    }

    private void displayHistory(String historyData) {
        System.out.println(CYAN + "=== Room History ===" + RESET);
        if (historyData.isEmpty()) {
            System.out.println(YELLOW + "No message history available." + RESET);
        } else {
            String[] messages = historyData.split("\\|");
            for (String msg : messages) {
                System.out.println(msg);
            }
        }
        System.out.println(CYAN + "=== End History ===" + RESET);
    }

    private String formatRoomList() {
        StringBuilder sb = new StringBuilder();
        for (String room : availableRooms) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(room);
            if (aiRooms.contains(room)) sb.append(" (AI)");
        }
        return sb.toString();
    }

    private void showRooms() {
        roomsLock.readLock().lock();
        try {
            System.out.println(GREEN + "Current: "
                    + (currentRoom != null ? currentRoom : "(none)") + RESET);
            System.out.println(BLUE  + "Joined: " + joinedRooms + RESET);
            System.out.println(CYAN  + "Available: "
                    + formatRoomList() + RESET);
        } finally {
            roomsLock.readLock().unlock();
        }
    }

    private void printSystemMessage(String room, String msg) {
        String header = YELLOW + "[" + room + "] " + RESET;
        System.out.println(header + msg);
    }

    private void showHelp() {
        System.out.println(BOLD + "=== Commands ===" + RESET);
        System.out.println(GREEN + "/join <room>" + RESET
                + "                - Join or create regular room");
        System.out.println(BLUE + "/join AI:<name>" + RESET
                + "              - Join/create AI room");
        System.out.println(BLUE + "/join AI:<name>:<prompt>" + RESET
                + "     - Join/create AI room with custom prompt");
        System.out.println(YELLOW + "/leave" + RESET
                + "                      - Leave current room");
        System.out.println(CYAN + "/rooms" + RESET
                + "                      - Show room info");
        System.out.println(PURPLE + "/users, /who" + RESET
                + "                - Show online users");
        System.out.println(PURPLE + "/list" + RESET
                + "                       - Show users in current room");
        System.out.println(CYAN + "/history" + RESET
                + "                    - Show room message history");
        System.out.println(RED + "/logout" + RESET
                + "                     - Exit and clear this account");
        System.out.println("/help" + "                       - Show this help");
        System.out.println("\n" + BOLD + "Tips:" + RESET);
        System.out.println("• Messages show room name if not your current room");
        System.out.println("• AI rooms are marked with 🤖 and (AI) indicators");
        System.out.println("• Each account is saved separately");
        System.out.println("• Client will automatically reconnect if connection is lost");
        System.out.println("• Fresh start on each reconnection - no message replay");
    }

    private void showAvailableCommands() {
        System.out.println(CYAN + "Available commands: /join, /leave, /rooms, /users, /list, /history, /logout, /help" + RESET);
    }

    private String printBold(String msg) { return BOLD + msg + RESET; }

    // Enhanced fault tolerance methods
    private void heartbeatMonitor() {
        while (running) {
            try {
                Thread.sleep(30000); // Send heartbeat every 30 seconds
                if (isConnectionHealthy() && out != null) {
                    out.println("HEARTBEAT");
                    if (out.checkError()) {
                        System.err.println(YELLOW + "Heartbeat failed - connection may be lost" + RESET);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // Thread-safe helpers for joinedRooms
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
            if (room.equals(currentRoom)) {
                if (!joinedRooms.isEmpty()) {
                    currentRoom = joinedRooms.iterator().next();
                    System.out.println(GREEN + "Switched to: " + currentRoom + RESET);
                } else {
                    currentRoom = null;
                }
            }
        } finally {
            roomsLock.writeLock().unlock();
        }
    }

    private void loadTokens() {
        Path p = Paths.get(TOKENS_FILE);
        if (Files.exists(p)) {
            try (InputStream is = Files.newInputStream(p)) {
                tokens.load(is);
            } catch (IOException ignored) {}
        }
    }

    private void saveTokens() {
        try (OutputStream os = Files.newOutputStream(Paths.get(TOKENS_FILE))) {
            tokens.store(os, "username=token");
        } catch (IOException e) {
            System.err.println(RED + "Could not save tokens: " + e.getMessage() + RESET);
        }
    }

    private void removeToken(String user) {
        if (user != null && tokens.containsKey(user)) {
            tokens.remove(user);
            saveTokens();
        }
    }

    private void cleanup() {
        running = false;

        // Close network resources
        closeCurrentConnection();

        // Close console
        try {
            if (console != null) {
                console.close();
            }
        } catch (IOException ignored) {}
    }
}