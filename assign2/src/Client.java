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
    private static final int SERVER_PORT = 8888;

    private static final String TRUSTSTORE_PATH = "chatserver.jks";
    private static final String TRUSTSTORE_PASSWORD = "password";
    private static final String TOKEN_FILE = "token.txt";

    private SSLSocket socket;
    private BufferedReader in;
    private PrintWriter out;
    private BufferedReader console;
    private String username;
    private String currentRoom;
    private volatile boolean running = true;

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
            console = new BufferedReader(new InputStreamReader(System.in));
            // establish initial connection
            connectSecure();
            authenticate();
            // start receiver thread
            Thread.startVirtualThread(this::receiveMessages);
            // handle user input on main thread
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
            socket.startHandshake();

            in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            // Read initial welcome
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
                authenticate();
                System.err.println("✅ Reconnected successfully.");
                return;
            } catch (Exception e) {
                System.err.println("Reconnect failed: " + e.getMessage());
            }
        }
    }

    private void authenticate() throws IOException {
        String token = loadToken();
        if (token != null) {
            out.println("TOKEN:" + token);
            String response = in.readLine();
            if (response != null && response.startsWith("SESSION_RESUMED:")) {
                username = response.substring("SESSION_RESUMED:".length());
                System.out.println("✅ Session resumed for " + username + "!");
                showHelp();
                return;
            } else {
                System.out.println("⚠️ Could not resume session: " + response);
            }
        }

        while (true) {
            System.out.print("\nEnter your username: ");
            String user = console.readLine();
            System.out.print("Enter your password: ");
            String pass = console.readLine();
            out.println("LOGIN:" + user + ":" + pass);

            String response = in.readLine();
            if (response == null) throw new IOException("Server disconnected during authentication");

            if (response.startsWith("AUTH_NEW:")) {
                username = user;
                saveToken(response.substring("AUTH_NEW:".length()));
                System.out.println("🎉 Welcome, new user " + username + "!");
                showHelp();
                break;
            } else if (response.startsWith("AUTH_OK:")) {
                username = user;
                saveToken(response.substring("AUTH_OK:".length()));
                System.out.println("✅ Logged in successfully as " + username + "!");
                showHelp();
                break;
            } else if (response.startsWith("AUTH_FAIL:")) {
                System.out.println("⚠️ Login failed: "
                        + response.substring("AUTH_FAIL:".length()));
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
                            String room = parts[0], sender = parts[1],
                                    text = parts[2], msgId = parts[3];
                            printMessage(room, sender, text);
                            out.println("ACK:" + msgId);  // <-- send ACK back
                        }
                    } else if (msg.startsWith("SYSTEM:")) {
                        String[] p = msg.substring("SYSTEM:".length()).split(":", 2);
                        printSystemMessage(p[0], p[1]);
                    } else if (msg.startsWith("ROOMS:")) {
                        updateAvailableRooms(msg.substring("ROOMS:".length()));
                    } else if (msg.startsWith("JOINED:")) {
                        handleJoinEvent(msg.substring("JOINED:".length()), false);
                    } else if (msg.startsWith("REJOINED:")) {
                        handleJoinEvent(msg.substring("REJOINED:".length()), true);
                    } else if (msg.startsWith("LEFT:")) {
                        String room = msg.substring("LEFT:".length());
                        removeJoinedRoom(room);
                        System.out.println(printBold("Left: ") + room);
                    } else {
                        System.out.println(msg);
                    }
                }
                // if we fall out of the inner loop, connection closed
                throw new IOException("Connection stream closed");
            } catch (IOException e) {
                if (running) {
                    System.err.println("⚠️ Connection lost. Attempting to reconnect...");
                    reconnect();
                }
            }
        }
    }

    private void handleJoinEvent(String room, boolean rejoined) {
        addJoinedRoom(room);
        currentRoom = room;
        String label = rejoined ? "Rejoined: " : "Joined: ";
        System.out.println(printBold(label) + room);
        if (aiRooms.contains(room)) {
            System.out.println(printBold("[INFO] AI room: bot will respond here"));
        }
    }

    private void handleInput() throws IOException {
        String line;
        while (running && (line = console.readLine()) != null) {
            if (line.startsWith("/")) {
                handleCommand(line);
            } else if (currentRoom != null) {
                out.println("MESSAGE:" + currentRoom + ":" + line);
            } else {
                System.out.println("You must join a room first (/join <room>)");
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
                    System.out.println(printBold("Joining: ") + arg);
                } else {
                    System.out.println("Usage: /join <room> or /join AI:<name>:<prompt>");
                }
                break;
            case "leave":
                if (currentRoom != null) {
                    out.println("LEAVE:" + currentRoom);
                    System.out.println(printBold("Leaving: ") + currentRoom);
                }
                break;
            case "rooms": showRooms(); break;
            case "logout":
                running = false;
                out.println("LOGOUT");
                break;
            case "help": showHelp(); break;
            default:
                System.out.println("Unknown command. Type /help");
        }
    }

    private void printMessage(String room, String sender, String content) {
        String prefix = sender.equals("Bot") ? "🤖 " : "";
        String header = room.equals(currentRoom)
                ? prefix + sender + ": "
                : "[" + room + "]" + prefix + sender + ": ";
        System.out.println(printBold(header) + content);
    }

    private void updateAvailableRooms(String list) {
        roomsLock.writeLock().lock();
        try {
            availableRooms.clear();
            aiRooms.clear();
            if (list != null && !list.isBlank()) {
                for (String token : list.split(",")) {
                    if (token.isBlank()) continue;
                    boolean isAI = token.endsWith(":AI");
                    String roomName = isAI
                            ? token.substring(0, token.length() - 3)
                            : token;
                    availableRooms.add(roomName);
                    if (isAI) aiRooms.add(roomName);
                }
            }
            System.out.println(printBold("Rooms: ") + availableRooms);
        } finally {
            roomsLock.writeLock().unlock();
        }
    }

    private void showRooms() {
        roomsLock.readLock().lock();
        try {
            System.out.println(printBold("Current: ")
                    + (currentRoom != null ? currentRoom : "(none)"));
            System.out.println(printBold("Joined: ") + joinedRooms);
            System.out.println(printBold("Available: ") + availableRooms);
        } finally {
            roomsLock.readLock().unlock();
        }
    }

    private void addJoinedRoom(String room) {
        roomsLock.writeLock().lock();
        try { joinedRooms.add(room); }
        finally { roomsLock.writeLock().unlock(); }
    }

    private void removeJoinedRoom(String room) {
        roomsLock.writeLock().lock();
        try {
            joinedRooms.remove(room);
            if (room.equals(currentRoom)) currentRoom = null;
        } finally { roomsLock.writeLock().unlock(); }
    }

    private void printSystemMessage(String room, String msg) {
        String header = "[" + room + "] SYSTEM: ";
        System.out.println(printBold(header) + msg);
    }

    private void showHelp() {
        System.out.println(printBold("--- Commands ---"));
        System.out.println("/join <room>                - Join or create regular room");
        System.out.println("/join AI:<name>:<prompt>    - Join or create AI room");
        System.out.println("/leave                      - Leave current room");
        System.out.println("/rooms                      - Show rooms");
        System.out.println("/logout                     - Exit");
        System.out.println("/help                       - Help\n");
    }

    private String printBold(String msg) { return BOLD + msg + RESET; }

    private String loadToken() {
        try {
            Path p = Paths.get(TOKEN_FILE);
            if (Files.exists(p)) {
                return Files.readString(p).trim();
            }
        } catch (IOException ignored) {}
        return null;
    }

    private void saveToken(String token) {
        try {
            Files.writeString(Paths.get(TOKEN_FILE), token, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Could not save token: " + e.getMessage());
        }
    }

    private void cleanup() {
        try { if (socket != null) socket.close(); }
        catch (IOException ignored) {}
    }
}
