# Secure Chat Server

A robust, enterprise-grade secure chat server implementation in Java featuring SSL/TLS encryption, AI integration, and advanced security measures.

## Features

### 🔐 Security & Authentication
- **TLS 1.3/1.2 Encryption** with strong cipher suites
- **PBKDF2 Password Hashing** (120,000 iterations, SHA-256)
- **Session Token Management** with automatic expiration
- **Rate Limiting** and IP-based connection limits
- **Enhanced Input Validation** and sanitization
- **Certificate-based SSL** with configurable keystores

### 💬 Chat Functionality
- **Multi-room Support** with real-time messaging
- **AI-powered Chat Rooms** with customizable prompts
- **Message History** with persistence
- **User Presence** tracking and online status
- **Room Management** (create, join, leave)
- **Message Acknowledgments** for delivery confirmation

### 🤖 AI Integration
- **Ollama Integration** for AI-powered responses
- **Custom AI Prompts** per room
- **Configurable AI Models** (default: llama3.2:1b)
- **Intelligent Bot Responses** in designated AI rooms

### 🛡️ Fault Tolerance
- **Automatic Reconnection** with exponential backoff
- **Heartbeat Monitoring** for connection health
- **Session Recovery** after disconnections
- **Message Timeout Handling**
- **Connection Pool Management**

## Client-Server Connection Architecture

### Overview
The chat server uses a **concurrent SSL socket architecture** with virtual threads to handle thousands of simultaneous connections. Each client maintains a persistent, encrypted connection with automatic reconnection and session management.

### Server Connection Handling

The server runs an infinite loop waiting for new client connections, and when someone connects, it immediately creates a new virtual thread to handle just that client. This means thousands of people can connect simultaneously without blocking each other.

The server accepts connections on a dedicated SSL socket and spawns lightweight virtual threads:

```java
// Main server loop - accepts connections 324 (in Server)
while (true) {
    Socket client = serverSocket.accept();
    String clientIp = client.getInetAddress().getHostAddress();
    
    // Check per-IP connection limits
    synchronized (connectionsPerIp) {
        int current = connectionsPerIp.getOrDefault(clientIp, 0);
        if (current >= MAX_CONN_PER_IP) {
            client.close();
            continue;
        }
        connectionsPerIp.put(clientIp, current + 1);
    }
    
    // Each client gets its own virtual thread
    Thread.startVirtualThread(() -> handleClient(client, clientIp));
}
```

Once a client is accepted, the server creates dedicated input/output streams wrapped in a try-with-resources block to ensure they're automatically closed. Each client gets its own isolated communication channel that won't interfere with others.

Each connection is handled independently with dedicated I/O streams:

```java
// 475 (in Server)
private void handleClient(Socket sock, String clientIp) {
    try (BufferedReader in = new BufferedReader(
            new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
         PrintWriter out = new PrintWriter(
            new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true)) {
        
        // Authentication phase
        while ((line = in.readLine()) != null) {
            if (line.startsWith("LOGIN:")) {
                // Handle login with rate limiting
                handleLogin(line, clientIp, out);
            } else if (line.startsWith("TOKEN:")) {
                // Resume session with existing token
                resumeSession(line, out);
            }
        }
        
        // Main message loop after authentication
        while ((line = in.readLine()) != null) {
            dispatch(line, handler); // Route to appropriate handler
        }
    }
}
```

### Client Connection Management

The client needs to establish a secure, encrypted connection to the server using SSL/TLS protocols. It configures specific security settings and creates input/output streams for two-way communication.

The client establishes a secure SSL connection with automatic reconnection:

```java
// 119 (in Client)
private void connectSecure() throws IOException {
    // SSL context setup with TLS 1.3
    SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
    // ... certificate validation ...
    
    SSLSocketFactory factory = sslContext.getSocketFactory();
    socket = (SSLSocket) factory.createSocket(SERVER_ADDRESS, SERVER_PORT);
    
    // Configure secure protocols and timeouts
    socket.setSoTimeout(30000);
    socket.setKeepAlive(true);
    socket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
    socket.startHandshake();
    
    // Create I/O streams
    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
}
```

### Bidirectional Communication Pattern

The server needs to send messages to multiple clients simultaneously when someone posts in a chat room. Each client handler maintains its own output stream to send messages independently without blocking others.

**Server → Client Broadcasting:**
```java
// 876 (in Server)
class ClientHandler {
    void receiveMessage(String roomName, String sender, String msg, String msgId) {
        if (active) {
            // Format: MESSAGE:room:sender:content:messageId
            out.println("MESSAGE:" + roomName + ":" + sender + ":" + msg + ":" + msgId);
        }
    }
}

// Broadcasting to all users in a room
void broadcast(String msg, String sender, String msgId) {
    for (ClientHandler user : users) {
        user.receiveMessage(name, sender, msg, msgId);
    }
}
```

The client runs two separate processes: one thread continuously listens for incoming messages from the server, while the main thread handles user keyboard input. This prevents the client from freezing when waiting for user input or server messages.

**Client → Server Messaging:**
```java
// Main thread handles user input - 521 (in Client)
private void handleInput() throws IOException {
    String line;
    while (running && (line = console.readLine()) != null) {
        if (line.startsWith("/")) {
            handleCommand(line); // Process commands
        } else if (currentRoom != null) {
            // Send message: MESSAGE:room:content
            out.println("MESSAGE:" + currentRoom + ":" + line);
        }
    }
}

// Separate thread receives server messages
private void receiveMessages() throws IOException {
    String msg;
    while ((msg = in.readLine()) != null) {
        if (msg.startsWith("MESSAGE:")) {
            // Parse: MESSAGE:room:sender:content:msgId
            String[] parts = msg.substring(8).split(":", 4);
            printMessage(parts[0], parts[1], parts[2]);
            out.println("ACK:" + parts[3]); // Acknowledge receipt
        }
    }
}
```

### Connection Health Monitoring

To detect when clients disconnect unexpectedly (like closing their laptop), the server periodically sends "heartbeat" messages and expects responses. If a client doesn't respond within a certain timeframe, the server assumes they're gone and cleans up their connection.

**Heartbeat System:**
```java
// Server sends periodic heartbeats - 375 (in Server)
private void heartbeatMonitorTask() {
    while (true) {
        Thread.sleep(HEARTBEAT_INTERVAL);
        // Check client timestamps
        if (now - lastHeartbeat > HEARTBEAT_INTERVAL * 2) {
            // Client appears disconnected
            cleanupStaleConnection(user);
        }
    }
}

// Client responds to heartbeats
if (msg.startsWith("HEARTBEAT")) {
    out.println("HEARTBEAT_ACK");
    lastHeartbeat = System.currentTimeMillis();
}
```

The client regularly checks if its connection to the server is still working by testing various socket properties. This helps detect network issues before trying to send important messages that might get lost.

**Connection Health Check:**
```java
// 553 (in Client)
private boolean isConnectionHealthy() {
    return socket != null &&
           !socket.isClosed() &&
           socket.isConnected() &&
           !socket.isInputShutdown() &&
           !socket.isOutputShutdown() &&
           (System.currentTimeMillis() - lastHeartbeat) < 60000;
}
```

### Automatic Reconnection

When the internet connection drops, the client doesn't just crash - it remembers what rooms you were in and tries to reconnect automatically. It uses exponential backoff, meaning it waits longer between each retry attempt to avoid overwhelming the server.

When connection is lost, the client automatically attempts to reconnect:

```java

// 205 (in Client)
private void handleConnectionLoss() {
    // Save current state
    String lastRoom = currentRoom;
    Set<String> roomsToRejoin = new HashSet<>(joinedRooms);
    
    while (running) {
        try {
            reconnect(); // Re-establish SSL connection
            
            // Restore previous state
            for (String room : roomsToRejoin) {
                out.println("REJOIN:" + room);
            }
            
            // Resume message handling
            Thread.startVirtualThread(this::receiveMessages);
            break;
        } catch (Exception e) {
            // Exponential backoff
            Thread.sleep(Math.min(baseDelay * attempts, maxDelay));
        }
    }
}
```

### Session Management

Instead of making users log in every time they reconnect, the server gives each user a unique token (like a temporary password) that stays valid for 24 hours. This token lets users resume their session instantly without re-entering credentials.

**Token-Based Sessions:**
```java
// Server creates secure session tokens - 759 (in Server)
private String createUserToken(String username) {
    byte[] tokenBytes = new byte[48];
    SecureRandom.getInstanceStrong().nextBytes(tokenBytes);
    String token = Base64.getEncoder().encodeToString(tokenBytes);
    
    userTokens.put(username, token);
    tokenToUser.put(token, username);
    tokenExpires.put(token, System.currentTimeMillis() + TOKEN_EXPIRY);
    
    return token;
}

// Client stores and reuses tokens - 291 (in Client)
private void authenticate() {
    String token = tokens.getProperty(username);
    if (token != null) {
        out.println("TOKEN:" + token);
        String response = in.readLine();
        if (response.startsWith("SESSION_RESUMED:")) {
            // Skip login, resume previous session
            return;
        }
    }
    // Fall back to username/password login
}
```

### Concurrency & Thread Safety

The server uses virtual threads (lightweight threads) to handle thousands of users simultaneously without consuming too much memory. It also uses read-write locks so multiple users can read data at the same time, but only one can modify it.

**Virtual Thread Model:**
- **Main Thread**: Accepts connections (`serverSocket.accept()`)
- **Per-Connection Threads**: Each user gets their own dedicated thread that handles all their chat activities like sending messages, joining rooms, and authentication without interfering with other users.

```java
// Every accepted client gets its own virtual thread - 340 (in Server)
Thread.startVirtualThread(() -> handleClient(client, clientIp));

// 475 (in Server)
private void handleClient(Socket sock, String clientIp) {
    // This entire method runs in the client's dedicated thread
    // Authentication, message handling, room management all happen here
    while ((line = in.readLine()) != null) {
        dispatch(line, handler); // Process this user's commands
    }
}
```

- **Background Threads**: The server runs several "housekeeping" threads that clean up expired sessions, monitor connection health, and generate AI responses without blocking the main chat functionality.

```java
// These background tasks run independently .- 319 (in Server)
Thread.startVirtualThread(this::sessionCleanupTask);     // Removes old sessions
Thread.startVirtualThread(this::heartbeatMonitorTask);   // Checks if users are still connected
Thread.startVirtualThread(this::connectionCleanupTask);  // Manages connection limits

// AI responses also run in background threads
Thread.startVirtualThread(() -> {
    String aiReply = getAIResponse(msg);  // Don't block chat while AI thinks
    broadcast(aiReply, "Bot");
});
```

Read-write locks allow multiple users to safely access shared data like user lists and room information simultaneously. When someone needs to modify this data (like joining a room), they get exclusive access to prevent data corruption.

**Thread-Safe Operations:**
```java
// Read-write locks for performance - 80 (in Server)
private final ReadWriteLock globalLock = new ReentrantReadWriteLock();

// Multiple users can read simultaneously
globalLock.readLock().lock();
try {
    // Read user data, room lists, etc.
} finally {
    globalLock.readLock().unlock();
}

// Exclusive access for modifications
globalLock.writeLock().lock();
try {
    // User registration, room changes
} finally {
    globalLock.writeLock().unlock();
}
```

### Key Benefits

- **Scalability**: Virtual threads support thousands of concurrent connections
- **Reliability**: Automatic reconnection with state preservation  
- **Security**: End-to-end SSL encryption with certificate validation
- **Efficiency**: Connection reuse through persistent sessions
- **Resilience**: Heartbeat monitoring and connection health checks
- **Performance**: Non-blocking I/O with message acknowledgments

This architecture ensures robust, secure, and efficient real-time communication between multiple clients and the server.

## Protocol Overview

### Authentication Flow
1. **Initial Connection**: SSL handshake with certificate validation
2. **Login Methods**:
   - `LOGIN:username:password` - New user registration or login
   - `TOKEN:session_token` - Resume existing session
3. **Session Management**: Automatic token generation and validation

### Message Protocol
- `MESSAGE:room:content` - Send message to room
- `JOIN:room_name` - Join or create regular room
- `JOIN:AI:room_name:prompt` - Create AI room with custom prompt
- `LEAVE:room_name` - Leave current room
- `HEARTBEAT` - Connection health check
- `ACK:message_id` - Acknowledge message delivery

### Server Responses
- `ROOMS:room1,room2:AI,room3` - Available rooms list
- `USERS:user1,user2,user3` - Online users
- `MESSAGE:room:sender:content:msg_id` - Incoming message
- `SYSTEM:room:message` - System notifications
- `HISTORY:message1|message2|...` - Room message history

## Requirements

### System Requirements
- **Java 17+** (Virtual Threads support)
- **Ollama** (for AI functionality)
- **OpenSSL** or Java keytool (for SSL certificates)

### Dependencies
- **org.json** library for AI API communication
- **Java Cryptography Architecture (JCA)**
- **Java Secure Socket Extension (JSSE)**

## Installation & Setup

### 1. Generate SSL Certificates

```bash
# Generate keystore with self-signed certificate
keytool -genkeypair \
  -alias chatserver \
  -keyalg RSA \
  -keysize 2048 \
  -validity 365 \
  -keystore chatserver.jks \
  -storepass password \
  -keypass password \
  -dname "CN=localhost, OU=ChatServer, O=MyOrg, L=City, ST=State, C=US"
```

### 2. Set Up Ollama (Optional - for AI features)

```bash

# Run with the chosen model
ollama run llama3.2:1b

```

## Running the Application

### Building

Compile both Java files:

```sh
# From inside the 'src' directory, run:
./build.sh
```

### Start the Server

```bash
chmod +x ./run_server.sh
./run_server.sh

```

### Start the Client

```bash
chmod +x ./run_client.sh
./run_client.sh
```

## Configuration

### Server Configuration
- **Port**: 8888 (configurable in `Server.java`)
- **Keystore**: `chatserver.jks` (override with system properties)
- **AI Endpoint**: `http://localhost:11434/api/generate`
- **Session Timeout**: 24 hours
- **Max Connections per IP**: 5
- **Rate Limiting**: 3 failed attempts per 10 minutes

### Security Parameters
- **PBKDF2 Iterations**: 120,000
- **Salt Length**: 32 bytes
- **Key Length**: 256 bits
- **Token Length**: 48 bytes (Base64 encoded)

## Usage Examples

### Basic Chat Commands
```
/join General          # Join the General room
/join AI:Helper        # Create/join AI room named "Helper"
/join AI:Tutor:You are a patient math tutor  # AI room with custom prompt
/leave                 # Leave current room
/rooms                 # Show all available rooms
/users                 # Show online users
/list                  # Show users in current room
/history               # View room message history
/logout                # Exit and clear session
/help                  # Show command help
```

### AI Room Features
- AI rooms are marked with 🤖 indicator
- Bot responds automatically to user messages
- Custom prompts can be set during room creation
- AI responses are generated using Ollama backend

## File Structure

```
(in src)
├── Server.java           # Main server implementation
├── Client.java           # Client application
├── chatserver.jks        # SSL keystore (generated)
├── auth.txt              # User credentials (auto-created)
├── sessions.txt          # Active sessions (auto-created)
├── tokens.properties     # Client session tokens (auto-created)
└── /lib/json-20231013.jar     # JSON library dependency
```