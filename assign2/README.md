# Concurrent Chat Server System

> Enterprise-grade multi-threaded chat server with advanced concurrency control

[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://www.oracle.com/java/)
[![Concurrency](https://img.shields.io/badge/Concurrency-Virtual%20Threads-blue.svg)](https://openjdk.org/jeps/425)
[![Security](https://img.shields.io/badge/Security-PBKDF2%20+%20JWT-green.svg)](https://en.wikipedia.org/wiki/PBKDF2)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A professional concurrent chat server implementation featuring advanced thread management, comprehensive security mechanisms, and robust connection monitoring. This system demonstrates enterprise-level concurrency patterns, thread safety guarantees, and scalable architecture design.

## Key Features

- **Advanced Concurrency Architecture**: Virtual thread-based client handling with sophisticated locking mechanisms
- **Multi-Layer Authentication System**: PBKDF2 password hashing with secure session token management
- **Thread-Safe Room Management**: Concurrent room creation/joining with race condition prevention
- **Comprehensive Connection Monitoring**: Heartbeat system with automatic stale connection cleanup
- **Enterprise Security**: Rate limiting, IP-based connection controls, and input sanitization
- **Message Reliability**: Acknowledgment tracking with timeout monitoring and automatic cleanup

## Architecture Overview

The system implements a sophisticated concurrent architecture handling multiple clients simultaneously:

| Component | Threading Model | Synchronization |
|-----------|----------------|-----------------|
| **Client Authentication** | Virtual threads per client | Global ReadWriteLock + synchronized maps |
| **Room Operations** | Shared thread pool | Global ReadWriteLock for room state |
| **Message Processing** | Dedicated virtual threads | Message-specific locks + acknowledgment tracking |
| **Connection Monitoring** | Background heartbeat threads | Dedicated heartbeat locks + cleanup routines |

## Quick Start

### Prerequisites

```bash
Java 17+
```

### Installation

```bash
git clone https://github.com/yourusername/concurrent-chat-server.git
cd concurrent-chat-server
javac *.java
```

### Running the Server

```bash
java Server
```

### Running the Client

```bash
java Client
```

## Project Structure

```
concurrent-chat-server/
├── Server.java                # Main server with concurrency management
├── Client.java                # Multi-threaded client implementation
├── users.txt                  # Encrypted user credentials storage
├── sessions.txt               # Active session token storage
└── README.md                  # This documentation
```

## Concurrency Architecture

### Thread Management System

The system uses Java's virtual threads to handle multiple clients efficiently:

- **Per-Client Processing**: Each client connection receives a dedicated virtual thread
- **Background System Threads**: Specialized threads handle session cleanup, connection maintenance, heartbeat monitoring, and message timeout tracking
- **Non-Blocking Operations**: Virtual threads prevent blocking on I/O operations

### Synchronization Mechanisms

#### Global ReadWriteLock
Protects critical shared data structures with optimized read/write access:
- User authentication data (salts, hashes, tokens)
- Room collections and user memberships
- Connection tracking maps

#### Specialized Lock Hierarchies
Dedicated locks prevent contention on high-frequency operations:
- Message timestamp tracking
- Client heartbeat monitoring
- Room state management

### Race Condition Prevention

The system implements comprehensive race condition prevention:

#### Authentication Protection
- Atomic check-then-act operations for user registration
- Exclusive write access during credential creation
- Thread-safe session token generation

#### Room Management Protection
- Atomic room creation and user assignment
- Concurrent join operations without conflicts
- Safe room state updates

## Security Architecture

### Multi-Layer Authentication

#### Enhanced Password Security
- PBKDF2 with secure random salts
- Configurable iteration counts for computational hardening
- Secure token generation with cryptographically strong randomness

#### Session Token Management
- 48-byte cryptographically secure tokens
- Automatic expiration tracking
- Thread-safe token validation and cleanup

### Rate Limiting & DDoS Protection

#### IP-Based Connection Limiting
Prevents resource exhaustion attacks with configurable connection limits per IP address.

#### Authentication Rate Limiting
Time-window based login attempt tracking prevents brute force attacks.

## Message Reliability System

### Acknowledgment Tracking
- Unique message ID generation
- Delivery confirmation tracking
- Automatic timeout detection and cleanup

### Timeout Monitoring
Background thread monitors unacknowledged messages and handles cleanup of stale message tracking data.

## Connection Health Monitoring

### Heartbeat System
Bidirectional heartbeat mechanism ensures connection health:
- Client sends periodic heartbeats to server
- Server tracks last heartbeat timestamps
- Automatic detection and cleanup of stale connections

### Stale Connection Detection
Server-side monitoring identifies inactive connections and performs automatic resource cleanup.

## Client-Side Concurrency

### Thread Separation
Client architecture separates concerns:
- Dedicated thread for message reception from server
- Main thread handles user input processing
- Background thread manages heartbeat transmission

### Thread-Safe State Management
Client protects shared state with dedicated ReadWriteLock mechanisms for room collections and user data.

## Performance Optimizations

### Lock Granularity
Fine-grained locking minimizes contention:
- Global lock for user data and rooms
- Message-specific locks for tracking
- Heartbeat-specific locks for health monitoring
- Synchronized collections for rate limiting

### Virtual Thread Efficiency
Lightweight virtual threads enable high concurrency:
- Isolated per-client processing contexts
- Non-blocking background system tasks
- Concurrent connection health monitoring

## Usage Examples

### Starting the Chat Server

```bash
# Start server on default port
java Server

# Multiple clients can connect simultaneously
java Client
```

### Basic Chat Operations

```bash
# Register/login with username and password
# Create or join chat rooms
# Send messages with automatic delivery tracking
# Real-time user list updates
```

### Connection Management

```bash
# Automatic heartbeat monitoring
# Graceful connection cleanup
# Session persistence across disconnections
```

## Configuration Parameters

| Parameter | Purpose |
|-----------|---------|
| `MAX_CONN_PER_IP` | IP-based connection limit |
| `MAX_LOGIN_FAILS` | Authentication attempt limit |
| `LOGIN_WINDOW_MS` | Rate limiting window |
| `TOKEN_EXPIRY` | Session duration |
| `HEARTBEAT_INTERVAL` | Health check frequency |
| `MESSAGE_TIMEOUT` | Message acknowledgment timeout |
| `PBKDF2_ITERATIONS` | Password hashing strength |

## Troubleshooting

### Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| **Connection Refused** | Server not running | Start server before clients |
| **Authentication Failed** | Rate limiting active | Wait for timeout window |
| **Message Delays** | Network congestion | Check heartbeat monitoring |
| **Memory Issues** | Too many connections | Adjust connection limits |

### Debug Information

The server provides comprehensive logging for:
- Authentication attempts and failures
- Room creation and user management
- Heartbeat monitoring and cleanup
- Message acknowledgment tracking

## Extending the System

### Adding New Features

1. **Custom Authentication**: Implement additional authentication providers
2. **Message Persistence**: Add database storage for message history
3. **File Transfer**: Extend protocol for binary data transmission
4. **Plugin System**: Create extensible service integration

### Performance Tuning

1. **Connection Pooling**: Optimize database connections
2. **Caching Layer**: Add Redis for session management
3. **Load Balancing**: Distribute across multiple server instances
4. **Metrics Collection**: Implement comprehensive monitoring
5. **Resource Limits**: Fine-tune memory and CPU usage

## Contributors

- **[Eduardo Cunha](https://github.com/educunhA04)** - up202207126
- **[Rodrigo Araújo](https://github.com/rodrigoaraujo9)** - up202205515

## License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.

```
MIT License

Copyright (c) 2024 Rodrigo Miranda, Eduardo Cunha, Rodrigo Araújo

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Acknowledgments

- Inspired by enterprise-grade chat systems and concurrent server architectures
- Implements advanced Java concurrency patterns and virtual thread technology
- Built upon established principles in distributed systems and network programming

---

**If this project helped you, please give it a star!**

[Report Bug](../../issues) • [Request Feature](../../issues) • [Documentation](../../wiki)
