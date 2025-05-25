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
```

### 2. Set Up Ollama (Optional - for AI features)

```bash

# Run with the chosen model
ollama run llama3.2:1b

```

## Running the Application

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
├── Server.java           # Main server implementation
├── Client.java           # Client application
├── chatserver.jks        # SSL keystore (generated)
├── auth.txt              # User credentials (auto-created)
├── sessions.txt          # Active sessions (auto-created)
├── tokens.properties     # Client session tokens (auto-created)
└── json-20231013.jar     # JSON library dependency
```