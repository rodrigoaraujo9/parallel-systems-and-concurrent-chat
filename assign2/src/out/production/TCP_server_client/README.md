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
keytool -genkeypair -alias chatserver -keyalg RSA -keysize 2048 \
        -validity 365 -keystore chatserver.jks \
        -dname "CN=localhost,OU=ChatServer,O=YourOrg,C=US" \
        -storepass password -keypass password

# For production, use proper CA-signed certificates
```

### 2. Set Up Ollama (Optional - for AI features)

```bash
# Install Ollama
curl -fsSL https://ollama.ai/install.sh | sh

# Pull the default model
ollama pull llama3.2:1b

# Start Ollama service
ollama serve
```

### 3. Compile the Project

```bash
# Download org.json library
wget https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar

# Compile server
javac -cp ".:json-20231013.jar" Server.java

# Compile client  
javac Client.java
```

## Running the Application

### Start the Server

```bash
# Basic startup (uses default paths)
java -cp ".:json-20231013.jar" Server

# Production startup with custom SSL config
java -Djavax.net.ssl.keyStore=./chatserver.jks \
     -Djavax.net.ssl.keyStorePassword=your_secure_password \
     -cp ".:json-20231013.jar" Server
```

### Start the Client

```bash
# Basic startup
java Client

# With custom SSL truststore
java -Djavax.net.ssl.trustStore=./chatserver.jks \
     -Djavax.net.ssl.trustStorePassword=password \
     Client
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
├── Server.java           # Main server implementation
├── Client.java           # Client application
├── chatserver.jks        # SSL keystore (generated)
├── auth.txt              # User credentials (auto-created)
├── sessions.txt          # Active sessions (auto-created)
├── tokens.properties     # Client session tokens (auto-created)
└── json-20231013.jar     # JSON library dependency
```

## Security Best Practices

### For Production Deployment
1. **Use proper CA-signed certificates** instead of self-signed
2. **Store keystore passwords securely** (environment variables, secret management)
3. **Configure firewall rules** to limit access
4. **Enable logging and monitoring**
5. **Regular security updates** and dependency scanning
6. **Use strong, unique passwords** for all keystores

### Network Security
- Server binds to all interfaces by default - restrict in production
- Consider running behind reverse proxy (nginx, Apache)
- Implement additional rate limiting at network level
- Use VPN or private networks for sensitive deployments

## Troubleshooting

### Common Issues

**SSL Handshake Failures**
```bash
# Verify keystore
keytool -list -keystore chatserver.jks

# Check certificate validity
keytool -list -v -keystore chatserver.jks
```

**AI Features Not Working**
```bash
# Check Ollama status
curl http://localhost:11434/api/tags

# Verify model availability
ollama list
```

**Connection Issues**
- Check firewall settings (port 8888)
- Verify SSL certificate validity
- Ensure client and server use compatible cipher suites