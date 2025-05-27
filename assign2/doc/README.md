### AI Output Stream Management

AI responses use same output stream protection as regular messages:

````java
// Lines 1225-1270 - Server.java (getAIResponse)
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

        // Sanitize and limit the prompt
        String sanitizedMsg = sanitizeInput(lastUserMsg, 500);
        String fullPrompt = aiPrompt + "\nUser: " + sanitizedMsg + "\nBot:";

        // HTTP request processing...

        String response = jsonResp.getString("response").trim();
        response = sanitizeInput(response, MAX_MSG_LEN);
        String finalResponse = response.isEmpty() ? "I'm not sure how to respond to that." : response;

        return finalResponse;

    } catch (java.net.ConnectException e) {
        return "Sorry, AI service is currently unavailable. Please make sure Ollama is running.";
    } catch (java.net.SocketTimeoutException e) {
        return "Sorry, AI service is taking too long to respond.";
    } catch (Exception e) {
        return "Sorry, I'm having trouble connecting to my AI service right now.";
    }
}
```# Chat Server Concurrency Report

## Authentication (Threads, Shared Data Structures)

### Thread Creation for Authentication
Each client gets dedicated virtual thread for independent auth processing:

```java
// Lines 324-340 - Server.java
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
````

### Shared Data Structures with Synchronization

Rate limiting uses synchronized collections to prevent concurrent modification:

```java
// Lines 102-104 - Server.java
private final Map<String,List<Long>> loginFailuresByIp = Collections.synchronizedMap(new HashMap<>());
private final Map<String,Integer> connectionsPerIp = Collections.synchronizedMap(new HashMap<>());

// Lines 500-515 - Server.java (handleClient method)
synchronized (loginFailuresByIp) {
    var fails = loginFailuresByIp.computeIfAbsent(clientIp, k -> new ArrayList<>());
    fails.removeIf(ts -> ts < now - LOGIN_WINDOW_MS);
    if (fails.size() >= MAX_LOGIN_FAILS) {
        out.println("AUTH_FAIL:Too many login attempts from your IP; please try later");
        recordFailure(clientIp, now);
        continue;
    }
}
```

### Global Read-Write Lock for User Data

Protects user credentials and session data from race conditions:

```java
// Lines 80-86 - Server.java
private final ReadWriteLock globalLock = new ReentrantReadWriteLock();
private final Map<String,String> userSalt = new HashMap<>();
private final Map<String,String> userHash = new HashMap<>();
private final Map<String,String> userTokens = new HashMap<>();
private final Map<String,String> tokenToUser = new HashMap<>();
private final Map<String,Long> tokenExpires = new HashMap<>();

// Lines 560-580 - Server.java (handleClient authentication)
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
    }
} finally {
    globalLock.writeLock().unlock();
}
```

### Session Token Management

Thread-safe token creation and validation with expiration tracking:

```java
// Lines 759-777 - Server.java
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
```

## Join Operations (Threads, Shared Data Structures)

### Room Creation Threading

Uses global write lock to prevent concurrent room creation conflicts:

```java
// Lines 945-970 - Server.java (ClientHandler.createOrJoin)
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
```

### Shared Room Data Structures

Connected users and room memberships protected by global lock:

```java
// Lines 88-92 - Server.java
private final Map<String,Room> rooms = new HashMap<>();
private final Map<String,Set<ClientHandler>> connectedUsers = new HashMap<>();
private final Map<String,Set<String>> userRooms = new HashMap<>();

// Lines 1080-1105 - Server.java (ClientHandler.broadcastUserList)
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
```

### Client-Side Room State Synchronization

Client uses read-write locks to protect room collections:

```java
// Lines 25-28 - Client.java
private final Set<String> joinedRooms = new HashSet<>();
private final Set<String> availableRooms = new HashSet<>();
private final Set<String> aiRooms = new HashSet<>();
private final ReadWriteLock roomsLock = new ReentrantReadWriteLock();

// Lines 780-787 - Client.java
private void addJoinedRoom(String room) {
    roomsLock.writeLock().lock();
    try {
        joinedRooms.add(room);
    } finally {
        roomsLock.writeLock().unlock();
    }
}

// Lines 720-730 - Client.java
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
```

## Message Reception (Threads, Shared Data, Output Stream)

### Client Message Reception Threading

Separate thread handles incoming messages to prevent blocking user input:

```java
// Lines 105-115 - Client.java
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
```

### Message Acknowledgment with Shared Data

Server tracks pending messages with dual-lock protection:

```java
// Lines 106-108 - Server.java
private final Map<String, Long> messageTimestamps = new HashMap<>();
private final ReadWriteLock messageTimestampsLock = new ReentrantReadWriteLock();

// Lines 1020-1040 - Server.java (ClientHandler.sendMessage)
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
```

### Output Stream Thread Safety

Each ClientHandler has dedicated output stream with concurrent access protection:

```java
// Lines 1065-1075 - Server.java (ClientHandler.receiveMessage)
void receiveMessage(String roomName, String sender, String msg, String msgId) {
    if (active) {
        if (sender == null)
            out.println("SYSTEM:" + roomName + ":" + msg);
        else
            out.println("MESSAGE:" + roomName + ":" + sender + ":" + msg + ":" + msgId);
    }
    // REMOVED: Message buffering for offline users
}

// Lines 1190-1200 - Server.java (Room.broadcast)
void broadcast(String msg, String sender, String msgId) {
    QueuedMessage qm = new QueuedMessage(name, sender, msg, msgId);
    history.add(qm);
    if (history.size() > MAX_HISTORY) history.remove(0);

    for (ClientHandler u : users) {
        u.receiveMessage(name, sender, msg, msgId);
    }
}
```

### Message Timeout Monitoring

Background thread monitors unacknowledged messages:

```java
// Lines 430-450 - Server.java
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
```

### Acknowledgment Handling

Thread-safe cleanup when messages are acknowledged:

```java
// Lines 905-920 - Server.java (ClientHandler.handleAck)
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
```

## AI Rooms (Threads, Shared Data, Output Stream)

### AI Response Threading

AI HTTP requests run in separate virtual threads to prevent blocking chat:

```java
// Lines 1205-1220 - Server.java (Room.broadcast)
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
```

### AI Room Shared Data

Room history and user lists shared between AI and normal message flows:

```java
// Lines 1155-1170 - Server.java (Room class)
private class Room {
    private final String name;
    private final boolean aiRoom;
    private final String aiPrompt;
    private final Set<ClientHandler> users = new HashSet<>();
    private final List<QueuedMessage> history = new ArrayList<>();

    void broadcast(String msg, String sender, String msgId) {
        QueuedMessage qm = new QueuedMessage(name, sender, msg, msgId);
        history.add(qm);
        if (history.size() > MAX_HISTORY) history.remove(0);

        System.out.println("DEBUG: Broadcasting message in room " + name + " from " + sender + ": " + msg);

        for (ClientHandler u : users) {
            u.receiveMessage(name, sender, msg, msgId);
        }
        // AI and normal messages use same broadcasting mechanism
    }
}
```

### AI Output Stream Management

AI responses use same output stream protection as regular messages:

```java
// Line 1200+ - Server.java (getAIResponse)
private String getAIResponse(String lastUserMsg) {
    try {
        URL url = new URL(AI_ENDPOINT);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(15000);

        // HTTP request processing...

        String response = jsonResp.getString("response").trim();
        return sanitizeInput(response, MAX_MSG_LEN);

    } catch (java.net.ConnectException e) {
        return "Sorry, AI service is currently unavailable.";
    } catch (Exception e) {
        return "Sorry, I'm having trouble connecting to my AI service.";
    }
}
```

### Client AI Room Identification

Client tracks AI rooms with thread-safe collections:

```java
// Line 30+ - Client.java
private final Set<String> aiRooms = new HashSet<>();

private void updateAvailableRooms(String list) {
    roomsLock.writeLock().lock();
    try {
        availableRooms.clear();
        aiRooms.clear();
        if (list != null && !list.isBlank()) {
            for (String token : list.split(",")) {
                boolean isAI = token.endsWith(":AI");
                String roomName = isAI ? token.substring(0, token.length() - 3) : token;
                availableRooms.add(roomName);
                if (isAI) aiRooms.add(roomName);
            }
        }
    } finally {
        roomsLock.writeLock().unlock();
    }
}
```

## Race Condition Prevention

### Concurrent Room Creation Race Condition

Prevents multiple clients from creating the same room simultaneously:

```java
// Lines 945-970 - Server.java (ClientHandler.createOrJoin)
void createOrJoin(String name, boolean isAI, String prompt) {
    globalLock.writeLock().lock();  // CRITICAL: Exclusive access
    try {
        boolean created = false;
        if (!rooms.containsKey(name)) {  // Check-then-act protected by lock
            rooms.put(name, new Room(name, isAI, aiPrompt));
            created = true;
        }
        Room room = rooms.get(name);  // Safe to get after check
        room.addUser(this);
        joined.add(name);
    } finally {
        globalLock.writeLock().unlock();
    }
}
```

### User Registration Race Condition

Prevents duplicate user creation during concurrent registration:

```java
// Lines 560-580 - Server.java (handleClient authentication)
globalLock.writeLock().lock();  // Exclusive write access
try {
    boolean isNew = !userSalt.containsKey(u);  // Atomic check
    if (isNew) {
        // Only one thread can register this username
        userSalt.put(u, saltHex);
        userHash.put(u, hashHex);
        saveUsers();  // Atomic file write
    } else {
        // Password verification for existing user
        byte[] expected = fromHex(userHash.get(u));
        // ... verification logic
    }
} finally {
    globalLock.writeLock().unlock();
}
```

### Connection Counting Race Condition

Prevents inaccurate connection counts with synchronized blocks:

```java
// Lines 330-340 - Server.java
synchronized (connectionsPerIp) {  // Atomic check-and-increment
    int current = connectionsPerIp.getOrDefault(clientIp, 0);
    if (current >= MAX_CONN_PER_IP) {
        client.close();
        continue;
    }
    connectionsPerIp.put(clientIp, current + 1);  // Atomic increment
}

// Lines 700-710 - Server.java (cleanup in finally block)
synchronized (connectionsPerIp) {  // Atomic decrement
    Integer count = connectionsPerIp.get(clientIp);
    if (count != null) {
        if (count <= 1) {
            connectionsPerIp.remove(clientIp);
        } else {
            connectionsPerIp.put(clientIp, count - 1);  // Atomic decrement
        }
    }
}
```

### Message Acknowledgment Race Condition

Prevents lost message tracking with dual-lock protection:

```java
// Lines 905-920 - Server.java (ClientHandler.handleAck)
void handleAck(String id) {
    globalLock.writeLock().lock();  // First lock
    try {
        pending.remove(id);  // Remove from pending messages
        messageTimestampsLock.writeLock().lock();  // Second lock
        try {
            messageTimestamps.remove(id);  // Consistent removal
        } finally {
            messageTimestampsLock.writeLock().unlock();
        }
    } finally {
        globalLock.writeLock().unlock();
    }
}
```

### Client Room State Race Condition

Prevents corrupted room collections on client side:

```java
// Lines 780-800 - Client.java
private void removeJoinedRoom(String room) {
    roomsLock.writeLock().lock();  // Exclusive modification
    try {
        joinedRooms.remove(room);
        if (room.equals(currentRoom)) {  // Check-then-act protected
            if (!joinedRooms.isEmpty()) {
                currentRoom = joinedRooms.iterator().next();
            } else {
                currentRoom = null;
            }
        }
    } finally {
        roomsLock.writeLock().unlock();
    }
}
```

## Summary

**Locks Implemented:**

- `globalLock` (ReadWriteLock): Protects user data, rooms, connections
- `messageTimestampsLock` (ReadWriteLock): Guards message timeout tracking
- `clientHeartbeatsLock` (ReadWriteLock): Synchronizes heartbeat monitoring
- `roomsLock` (ReadWriteLock): Client-side room collection protection
- `synchronized` blocks: Rate limiting and connection counting

**Shared Data Structures:**

- User authentication maps (salt, hash, tokens)
- Room collections and user memberships
- Connection tracking and rate limiting maps
- Message acknowledgment tracking
- Heartbeat timestamps

**Thread Safety Guarantees:**

- Concurrent reads with exclusive writes via ReadWriteLock
- Atomic operations on synchronized collections
- Isolated per-client processing with virtual threads
- Non-blocking AI processing with fault tolerance
