import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.*;

public class Server {
    private static final int PORT = 8888;
    private static final String USERS_FILE = "auth.txt";
    private static final String AI_ENDPOINT = "http://localhost:11434/api/generate";

    // User credentials
    private final Map<String, String> users = new HashMap<>();
    private final ReadWriteLock usersLock = new ReentrantReadWriteLock();

    // Chat rooms
    private final Map<String, Room> rooms = new HashMap<>();
    private final ReadWriteLock roomsLock = new ReentrantReadWriteLock();

    // Connected clients
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
            if (Files.exists(Paths.get(USERS_FILE))) {
                List<String> lines = Files.readAllLines(Paths.get(USERS_FILE));
                for (String line : lines) {
                    String[] parts = line.split(":");
                    if (parts.length == 2) {
                        users.put(parts[0], parts[1]);
                    }
                }
                System.out.println("Loaded " + users.size() + " users.");
            } else {
                users.put("alice", "pass");
                users.put("bob", "pass");
                users.put("mariana", "pass");
                saveUsers();
                System.out.println("Created default users.");
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
            for (Map.Entry<String, String> entry : users.entrySet()) {
                lines.add(entry.getKey() + ":" + entry.getValue());
            }
            Files.write(Paths.get(USERS_FILE), lines);
        } catch (IOException e) {
            System.err.println("Error saving users: " + e.getMessage());
        } finally {
            readLock.unlock();
        }
    }

    private void createDefaultRooms() {
        Lock writeLock = roomsLock.writeLock();
        writeLock.lock();
        try {
            rooms.put("Sala", new Room("Sala", false, null));
            rooms.put("Cozinha", new Room("Cozinha", false, null));
            System.out.println("Created default rooms: " + rooms.keySet());
        } finally {
            writeLock.unlock();
        }
    }

    private void start() {
        System.out.println("Server starting on port " + PORT);
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server successfully started and listening on port " + PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New connection accepted from " + clientSocket.getInetAddress());
                Thread.startVirtualThread(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
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
            System.err.println("Error handling client: " + e.getMessage());
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
        usersLock.readLock().lock();
        try {
            return pass.equals(users.get(user));
        } finally {
            usersLock.readLock().unlock();
        }
    }

    private boolean registerUser(String user, String pass) {
        usersLock.writeLock().lock();
        try {
            if (users.containsKey(user)) return false;
            users.put(user, pass);
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
            for (String room : rooms.keySet()) {
                sb.append(room).append(",");
            }
            out.println(sb.toString());
        } finally {
            roomsLock.readLock().unlock();
        }
    }

    // ClientHandler inner class
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
                out.println("ERROR:Room does not exist: " + roomName);
                return;
            }
            room.addUser(this);
            joinedLock.writeLock().lock();
            try {
                joinedRooms.add(roomName);
            } finally {
                joinedLock.writeLock().unlock();
            }
            room.broadcast(username + " enters the room", null);
            sendHistory(room);
            out.println("JOINED:" + roomName);
        }

        void createRoom(String name, boolean isAI, String prompt) {
            roomsLock.writeLock().lock();
            try {
                if (rooms.containsKey(name)) {
                    out.println("ERROR:Room already exists: " + name);
                    return;
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
                    room.broadcast(username + " leaves the room", null);
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
            List<String> hist = room.getHistory();
            for (String h : hist) out.println("HISTORY:" + room.getName() + ":" + h);
        }

        void cleanup() {
            joinedLock.writeLock().lock();
            try {
                for (String rn : new ArrayList<>(joinedRooms)) {
                    leaveRoom(rn);
                }
            } finally {
                joinedLock.writeLock().unlock();
            }
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    // Room inner class
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
            } finally {
                historyLock.writeLock().unlock();
            }
            usersLock.readLock().lock();
            try {
                for (ClientHandler u : users) {
                    u.receiveMessage(name, sender, msg);
                }
            } finally {
                usersLock.readLock().unlock();
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