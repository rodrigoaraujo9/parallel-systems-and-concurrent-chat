// Client.java
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

    private static final String TRUSTSTORE_PATH     = "chatserver.jks";
    private static final String TRUSTSTORE_PASSWORD = "password";
    private static final String TOKENS_FILE         = "tokens.properties";

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

    // ANSI color codes
    private static final String RESET  = "\u001B[0m";
    private static final String BOLD   = "\u001B[1m";
    private static final String GREEN  = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BLUE   = "\u001B[34m";
    private static final String CYAN   = "\u001B[36m";

    // Store multiple tokens
    private final Properties tokens = new Properties();

    public static void main(String[] args) {
        new Client().start();
    }

    public void start() {
        try {
            console = new BufferedReader(new InputStreamReader(System.in));
            connectSecure();
            authenticate();             // initial or reconnect auth
            Thread.startVirtualThread(this::receiveMessages);
            handleInput();
        } catch (Exception e) {
            System.err.println("Client error: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    private void connectSecure() throws IOException {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            KeyStore ts = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(TRUSTSTORE_PATH)) {
                ts.load(fis, TRUSTSTORE_PASSWORD.toCharArray());
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            sslContext.init(null, tmf.getTrustManagers(), null);

            SSLSocketFactory factory = sslContext.getSocketFactory();
            socket = (SSLSocket) factory.createSocket(SERVER_ADDRESS, SERVER_PORT);

            // Enforce hostname verification
            SSLParameters sslParams = socket.getSSLParameters();
            sslParams.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(sslParams);

            socket.startHandshake();

            in  = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            String welcome = in.readLine();
            System.out.println(welcome);
        } catch (NoSuchAlgorithmException | KeyStoreException |
                 CertificateException | KeyManagementException e) {
            throw new IOException("SSL setup failed: " + e.getMessage(), e);
        }
    }

    private void reconnect() {
        while (running) {
            try {
                Thread.sleep(5000);
                connectSecure();
                authenticate();    // auto-resume on reconnect if possible
                System.err.println(GREEN + "✅ Reconnected successfully." + RESET);
                return;
            } catch (Exception e) {
                System.err.println(RED + "Reconnect failed: " + e.getMessage() + RESET);
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
                    System.out.println(GREEN + "✅ Session resumed for " + username + "!" + RESET);
                    showHelp();
                    return;
                } else {
                    System.out.println(YELLOW + "⚠️ Could not resume session: " + response + RESET);
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
                    System.out.println(GREEN + "✅ Session resumed for " + username + "!" + RESET);
                    showHelp();
                    return;
                } else {
                    System.out.println(YELLOW + "⚠️ Could not resume session: " + response + RESET);
                    tokens.remove(username);
                    saveTokens();
                }
            }
        }

        // Fresh login flow
        while (true) {
            System.out.print("\nEnter your username (3+ chars, alphanumeric/underscore/dash only): ");
            String user = console.readLine();
            if (user == null) throw new IOException("Input stream closed");

            System.out.print("Enter your password: ");
            String pass = console.readLine();
            if (pass == null) throw new IOException("Input stream closed");

            out.println("LOGIN:" + user + ":" + pass);
            String response = in.readLine();
            if (response == null) throw new IOException("Server disconnected during authentication");

            if (response.startsWith("AUTH_NEW:")) {
                username = user;
                String token = response.substring("AUTH_NEW:".length());
                tokens.setProperty(username, token);
                saveTokens();
                System.out.println(GREEN + "🎉 Welcome, new user " + username + "!" + RESET);
                showHelp();
                break;
            } else if (response.startsWith("AUTH_OK:")) {
                username = user;
                String token = response.substring("AUTH_OK:".length());
                tokens.setProperty(username, token);
                saveTokens();
                System.out.println(GREEN + "✅ Logged in successfully as " + username + "!" + RESET);
                showHelp();
                break;
            } else if (response.startsWith("AUTH_FAIL:")) {
                System.out.println(RED + "⚠️ Login failed: "
                        + response.substring("AUTH_FAIL:".length()) + RESET);
            } else {
                System.out.println("Unexpected response: " + response);
            }
        }
    }

    private void receiveMessages() {
        while (running) {
            try {
                String msg;
                while ((msg = in.readLine()) != null) {
                    if (msg.startsWith("MESSAGE:")) {
                        String body = msg.substring("MESSAGE:".length());
                        String[] parts = body.split(":", 4);
                        if (parts.length == 4) {
                            String room   = parts[0],
                                    sender = parts[1],
                                    text   = parts[2],
                                    msgId  = parts[3];
                            printMessage(room, sender, text);
                            out.println("ACK:" + msgId);
                        }
                    } else if (msg.startsWith("SYSTEM:")) {
                        String[] p = msg.substring("SYSTEM:".length()).split(":", 2);
                        if (p.length == 2) printSystemMessage(p[0], p[1]);
                    } else if (msg.startsWith("ROOMS:")) {
                        updateAvailableRooms(msg.substring("ROOMS:".length()));
                    } else if (msg.startsWith("CREATED:")) {
                        String room = msg.substring("CREATED:".length());
                        System.out.println(GREEN + printBold("Created room: ") + room + RESET);
                    } else if (msg.startsWith("JOINED:")) {
                        handleJoinEvent(msg.substring("JOINED:".length()), false);
                    } else if (msg.startsWith("REJOINED:")) {
                        handleJoinEvent(msg.substring("REJOINED:".length()), true);
                    } else if (msg.startsWith("LEFT:")) {
                        String room = msg.substring("LEFT:".length());
                        removeJoinedRoom(room);
                        System.out.println(YELLOW + printBold("Left: ") + room + RESET);
                    } else if (msg.equals("BYE")) {
                        System.out.println(GREEN + "👋 Goodbye!" + RESET);
                        running = false;
                        break;
                    } else {
                        System.out.println(msg);
                    }
                }
                throw new IOException("Connection stream closed");
            } catch (IOException e) {
                if (running) {
                    System.err.println(RED + "⚠️ Connection lost. Attempting to reconnect..." + RESET);
                    reconnect();
                }
            }
        }
    }

    private void handleJoinEvent(String room, boolean rejoined) {
        addJoinedRoom(room);
        currentRoom = room;
        String label = rejoined ? "Rejoined: " : "Joined: ";
        String color = rejoined ? CYAN : GREEN;
        System.out.println(color + printBold(label) + room + RESET);
        if (aiRooms.contains(room)) {
            System.out.println(BLUE + printBold("[INFO] AI room: bot will respond here") + RESET);
        }
    }

    private void handleInput() throws IOException {
        String line;
        while (running && (line = console.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("/")) {
                handleCommand(line);
            } else if (currentRoom != null) {
                out.println("MESSAGE:" + currentRoom + ":" + line);
            } else {
                System.out.println(YELLOW + "You must join a room first (/join <room>)" + RESET);
            }
        }
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
                            String prompt  = aiParts.length == 3 ? aiParts[2] : "";
                            out.println("JOIN:AI:" + roomName + (prompt.isEmpty() ? "" : ":" + prompt));
                            System.out.println(BLUE + printBold("Creating/Joining AI room: ") + roomName + RESET);
                            if (!prompt.isEmpty())
                                System.out.println(BLUE + "AI Prompt: " + prompt + RESET);
                        } else {
                            System.out.println(RED + "Usage: /join AI:<name> or /join AI:<name>:<prompt>" + RESET);
                        }
                    } else {
                        out.println("JOIN:" + arg);
                        System.out.println(GREEN + printBold("Joining: ") + arg + RESET);
                    }
                } else {
                    System.out.println(YELLOW + "Usage: /join <room> or /join AI:<name>:<prompt>" + RESET);
                }
            }
            case "leave" -> {
                if (currentRoom != null) {
                    out.println("LEAVE:" + currentRoom);
                    System.out.println(YELLOW + printBold("Leaving: ") + currentRoom + RESET);
                } else {
                    System.out.println(YELLOW + "You are not in any room." + RESET);
                }
            }
            case "switch" -> {
                if (!arg.isBlank()) {
                    roomsLock.readLock().lock();
                    try {
                        if (joinedRooms.contains(arg)) {
                            currentRoom = arg;
                            System.out.println(GREEN + printBold("Switched to: ") + arg + RESET);
                        } else {
                            System.out.println(RED + "You are not in room: " + arg + RESET);
                        }
                    } finally {
                        roomsLock.readLock().unlock();
                    }
                } else {
                    System.out.println(YELLOW + "Usage: /switch <room>" + RESET);
                }
            }
            case "rooms"   -> showRooms();
            case "logout"  -> { running = false; out.println("LOGOUT"); removeToken(username); }
            case "help"    -> showHelp();
            case "clear"   -> System.out.print("\033[2J\033[H");
            case "status"  -> showStatus();
            default        -> System.out.println(RED + "Unknown command. Type /help" + RESET);
        }
    }

    private void printMessage(String room, String sender, String content) {
        if (content.matches(".*:[0-9A-Fa-f]{8}$")) {
            int idx = content.lastIndexOf(':');
            content = content.substring(0, idx);
        }
        String prefix      = sender.equals("Bot") ? "🤖 " : "";
        String senderColor = sender.equals("Bot") ? BLUE
                : sender.equals(username) ? GREEN : RESET;
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
            System.out.println(CYAN + printBold("Available rooms: ")
                    + formatRoomList() + RESET);
        } finally {
            roomsLock.writeLock().unlock();
        }
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
            System.out.println(GREEN + printBold("Current: ")
                    + (currentRoom != null ? currentRoom : "(none)") + RESET);
            System.out.println(BLUE  + printBold("Joined: ") + joinedRooms + RESET);
            System.out.println(CYAN  + printBold("Available: ")
                    + formatRoomList() + RESET);
        } finally {
            roomsLock.readLock().unlock();
        }
    }

    private void showStatus() {
        roomsLock.readLock().lock();
        try {
            System.out.println(printBold("=== Status ==="));
            System.out.println(GREEN + "Username: " + username + RESET);
            System.out.println(GREEN + "Current room: "
                    + (currentRoom != null ? currentRoom : "(none)") + RESET);
            System.out.println(BLUE + "Joined rooms: " + joinedRooms + RESET);
            System.out.println(CYAN + "Available rooms: "
                    + formatRoomList() + RESET);
        } finally {
            roomsLock.readLock().unlock();
        }
    }

    private void printSystemMessage(String room, String msg) {
        String header = YELLOW + "[" + room + "] SYSTEM: " + RESET;
        System.out.println(header + msg);
    }

    private void showHelp() {
        System.out.println(printBold("=== Commands ==="));
        System.out.println(GREEN  + "/join <room>"                    + RESET
                + "                 - Join or create regular room");
        System.out.println(BLUE   + "/join AI:<name>"                + RESET
                + "              - Join/create AI room");
        System.out.println(BLUE   + "/join AI:<name>:<prompt>"      + RESET
                + "     - Join/create AI room with prompt");
        System.out.println(YELLOW + "/leave"                        + RESET
                + "                       - Leave current room");
        System.out.println(CYAN   + "/switch <room>"                + RESET
                + "               - Switch to another joined room");
        System.out.println(CYAN   + "/rooms"                        + RESET
                + "                       - Show room info");
        System.out.println(GREEN  + "/status"                       + RESET
                + "                      - Show status");
        System.out.println("/clear"                                  + "                       - Clear screen");
        System.out.println(RED    + "/logout"                       + RESET
                + "                      - Exit and clear this account");
        System.out.println("/help"                                   + "                        - Show this help");
        System.out.println("\n" + printBold("Tips:"));
        System.out.println("• Messages show room name if not your current room");
        System.out.println("• AI rooms are marked with 🤖 and (AI) indicators");
        System.out.println("• Each account is saved separately in tokens.properties");
    }

    private String printBold(String msg) { return BOLD + msg + RESET; }

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
                    System.out.println(GREEN + printBold("Switched to: ") + currentRoom + RESET);
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
        try { if (socket != null) socket.close(); }
        catch (IOException ignored) {}
    }
}
