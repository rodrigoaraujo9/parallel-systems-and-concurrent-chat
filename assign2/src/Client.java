import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.*;

public class Client {
    // Configuration
    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 8888;

    // Client state
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private BufferedReader consoleReader;
    private String username;
    private String authToken;
    private String currentRoom;
    private boolean running = true;

    // Thread-safe joined rooms
    private final Set<String> joinedRooms = new HashSet<>();
    private final ReadWriteLock joinedRoomsLock = new ReentrantReadWriteLock();

    public static void main(String[] args) {
        new Client().start();
    }

    public void start() {
        try {
            connect();

            // Authenticate user (login/register or token)
            authenticate();

            // Start message receiver
            Thread.startVirtualThread(this::receiveMessages);

            // Main input loop
            String input;
            while (running && (input = consoleReader.readLine()) != null) {
                processUserInput(input);
            }

            cleanUp();
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        }
    }

    private void connect() throws IOException {
        try {
            socket = new Socket(SERVER_ADDRESS, SERVER_PORT);

            System.out.println("Establishing connection to " + SERVER_ADDRESS + ":" + SERVER_PORT);

            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);
            consoleReader = new BufferedReader(new InputStreamReader(System.in));
            System.out.println("Connected to server at " + SERVER_ADDRESS + ":" + SERVER_PORT);
        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
            throw e;
        }
    }

    private void authenticate() throws IOException {
        String response = in.readLine(); // AUTH: welcome
        System.out.println(response.substring(5));

        boolean authenticated = false;
        while (!authenticated) {
            System.out.println("1. Login\n2. Register");
            System.out.print("Choose an option: ");
            String choice = consoleReader.readLine();

            if (choice.equals("1")) {
                System.out.print("Username: ");
                String user = consoleReader.readLine();
                System.out.print("Password: ");
                String pass = consoleReader.readLine();
                out.println("LOGIN:" + user + ":" + pass);

            } else if (choice.equals("2")) {
                System.out.print("New username: ");
                String user = consoleReader.readLine();
                System.out.print("New password: ");
                String pass = consoleReader.readLine();
                out.println("REGISTER:" + user + ":" + pass);

            } else {
                // Attempt token auth
                if (username != null && authToken != null) {
                    out.println("TOKEN:" + username + ":" + authToken);
                } else {
                    System.out.println("Invalid choice. Please choose 1 or 2.");
                    continue;
                }
            }

            response = in.readLine();
            if (response.startsWith("AUTH_OK:")) {
                // Format: AUTH_OK:username:token
                String[] parts = response.substring(8).split(":", 2);
                this.username = parts[0];
                if (parts.length > 1) {
                    this.authToken = parts[1];
                }
                authenticated = true;
                System.out.println("Authentication successful. Welcome, " + username + "!");
                showCommands();
                break;
            } else {
                System.out.println("Authentication failed: " + response.substring(response.indexOf(':')+1));
            }
        }
    }

    private void receiveMessages() {
        try {
            String message;
            while (running && (message = in.readLine()) != null) {
                handleServerMessage(message);
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Connection lost: " + e.getMessage());
                reconnect();
            }
        }
    }

    private void handleServerMessage(String message) {
        if (message.startsWith("MESSAGE:")) {
            // MESSAGE:room:sender:msg
            String[] parts = message.substring(8).split(":", 3);
            if (parts.length == 3) {
                String room = parts[0], sender = parts[1], content = parts[2];
                printMessage(room, sender, content);
            }
        } else if (message.startsWith("SYSTEM:")) {
            String[] parts = message.substring(7).split(":", 2);
            printSystem(parts[0], parts[1]);
        } else if (message.startsWith("HISTORY:")) {
            String[] parts = message.substring(8).split(":", 2);
            if (currentRoom != null && currentRoom.equals(parts[0])) {
                System.out.println(parts[1]);
            }
        } else if (message.startsWith("JOINED:")) {
            String room = message.substring(7);
            lockJoinedRooms(true, room);
            currentRoom = room;
            System.out.println("You have joined " + room);
            System.out.println("Type your messages. Type /help for commands.");
        } else if (message.startsWith("LEFT:")) {
            String room = message.substring(5);
            lockJoinedRooms(false, room);
            if (room.equals(currentRoom)) currentRoom = null;
            System.out.println("You have left " + room);
        } else if (message.startsWith("CREATED:")) {
            System.out.println("Created room: " + message.substring(8));
        } else if (message.startsWith("ERROR:")) {
            System.out.println("Error: " + message.substring(6));
        } else if (message.startsWith("ROOMS:")) {
            System.out.println("Available rooms: " + message.substring(6));
        } else {
            System.out.println("Server: " + message);
        }
    }

    private void lockJoinedRooms(boolean add, String room) {
        Lock lock = joinedRoomsLock.writeLock();
        lock.lock();
        try {
            if (add) joinedRooms.add(room);
            else joinedRooms.remove(room);
        } finally {
            lock.unlock();
        }
    }

    private void printMessage(String room, String sender, String content) {
        if (room.equals(currentRoom)) System.out.println(sender + ": " + content);
        else System.out.println("[" + room + "] " + sender + ": " + content);
    }

    private void printSystem(String room, String content) {
        if (room.equals(currentRoom)) System.out.println("[" + content + "]");
        else System.out.println("[" + room + "] [" + content + "]");
    }

    private void processUserInput(String input) {
        if (input.startsWith("/")) {
            String[] parts = input.substring(1).split(" ", 2);
            String cmd = parts[0].toLowerCase();
            switch (cmd) {
                case "join":
                    if (parts.length>1) out.println("JOIN:"+parts[1]);
                    else System.out.println("Usage: /join roomName");
                    break;
                case "create":
                    contextCreate(parts);
                    break;
                case "leave":
                    if (currentRoom!=null) out.println("LEAVE:"+currentRoom);
                    else System.out.println("You are not in any room.");
                    break;
                case "switch":
                    if (parts.length>1 && lockContains(parts[1])) {
                        currentRoom = parts[1];
                        System.out.println("Switched to " + currentRoom);
                    } else System.out.println("Usage: /switch roomName");
                    break;
                case "rooms":
                    System.out.println("Joined rooms: " + getJoinedRooms());
                    System.out.println("Current room: " + (currentRoom!=null?currentRoom:"None"));
                    break;
                case "logout":
                    out.println("LOGOUT");
                    running=false;
                    break;
                case "help":
                    showCommands();
                    break;
                default: // send message
                    if (currentRoom!=null) out.println("MESSAGE:"+currentRoom+":"+input);
                    else System.out.println("Join a room first.");
            }
        } else {
            if (currentRoom!=null) out.println("MESSAGE:"+currentRoom+":"+input);
            else System.out.println("You are not in any room. /join first.");
        }
    }

    private void contextCreate(String[] parts) {
        if (parts.length>1) out.println("CREATE:"+parts[1]);
        else System.out.println("Usage: /create roomName");
    }

    private boolean lockContains(String room) {
        Lock lock = joinedRoomsLock.readLock();
        lock.lock();
        try {
            return joinedRooms.contains(room);
        }
        finally {
            lock.unlock();
        }
    }

    private String getJoinedRooms() {
        Lock lock = joinedRoomsLock.readLock();
        lock.lock();
        try {
            return String.join(", ", joinedRooms);
        } finally {
            lock.unlock();
        }
    }

    private void reconnect() {
        running = true;
        try {
            connect();
            if (username != null && authToken != null) {
                out.println("TOKEN:" + username + ":" + authToken);
                String resp = in.readLine();
                if (resp.startsWith("AUTH_OK:")) {
                    System.out.println("Reconnected successfully!");
                    if (currentRoom != null) out.println("JOIN:" + currentRoom);
                    Thread.startVirtualThread(this::receiveMessages);
                } else {
                    System.out.println("Reconnection failed: " + resp);
                    authenticate();
                }
            } else {
                authenticate();
            }
        } catch (IOException e) {
            System.err.println("Reconnection error: " + e.getMessage());
            try {
                Thread.sleep(5000);
                reconnect();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void showCommands() {
        System.out.println("\nCommands: /join, /create, /leave, /switch, /rooms, /logout, /help");
    }

    private void cleanUp() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        System.out.println("Disconnected.");
    }
}