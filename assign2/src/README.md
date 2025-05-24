# Secure Chat Application

This repository contains a secure chat server and client implemented in Java. The server uses TLS for secure communication and supports persistent authentication tokens, chat rooms (including AI-assisted rooms), and automatic reconnection. The client connects securely, manages sessions, and provides a command-line interface.

## Prerequisites

* Java SE 17 or higher
* `keytool` (part of JDK) to generate keystore/truststore
* Network access (localhost or configured server address)

## Repository Structure

```
├── Server.java          # Main server implementation
├── Client.java          # Main client implementation
├── chatserver.jks       # Java KeyStore for TLS (used by both server and client)
├── auth.txt             # Stores user credentials (username:salt:hash)
├── sessions.txt         # Stores active session tokens
├── tokens.properties    # Client-side storage for session tokens
└── README.md            # This file
```

## Generating Keystore and Truststore

1. **Generate server keystore** (if not already provided):

   ```sh
   keytool -genkeypair -alias chatserver -keyalg RSA -keysize 2048 \
     -storetype JKS -keystore chatserver.jks -validity 3650
   ```
2. **Export server certificate**:

   ```sh
   keytool -exportcert -alias chatserver -keystore chatserver.jks \
     -file chatserver.crt
   ```
3. **Create client truststore** (using the same `chatserver.jks` as truststore):

   ```sh
   # If you want a separate truststore:
   keytool -importcert -alias chatserver -file chatserver.crt \
     -keystore clienttruststore.jks
   ```

   > **Note:** By default, the client uses `chatserver.jks` as its truststore.

## Building

Compile both Java files:

```sh
javac Server.java Client.java
```

## Running the Server

1. Ensure `chatserver.jks`, `auth.txt`, and `sessions.txt` are in the working directory.
2. Start the server:

   ```sh
   java Server
   ```
3. The server listens on port `8888` and enforces TLSv1.2/1.3 with strong cipher suites.

## Running the Client

1. Ensure `chatserver.jks` (as truststore) and `tokens.properties` are in the working directory.
2. Start the client:

   ```sh
   java Client
   ```
3. Follow the prompts to log in or resume a session.

## Client Commands

* `/join <room>`: Join or create a regular chat room.
* `/join AI:<name>`: Join or create an AI-assisted room.
* `/join AI:<name>:<prompt>`: Provide an initial AI prompt.
* `/leave`: Leave the current room.
* `/switch <room>`: Switch your active room.
* `/rooms`: Show available and joined rooms.
* `/status`: Show client and room status.
* `/test`: Test connection health.
* `/reconnect`: Force a reconnection attempt.
* `/clear`: Clear the console screen.
* `/logout`: Exit and clear the current account.
* `/help`: Show this help message.

## Notes

* **Authentication**: Credentials are stored using PBKDF2 with HMAC-SHA256. New users are registered on first login.
* **Session Tokens**: Tokens expire after 24 hours and are stored in `sessions.txt` (server) and `tokens.properties` (client).
* **Automatic Reconnection**: The client automatically attempts reconnection with exponential backoff when the connection is lost.
* **AI Rooms**: Messages in AI-enabled rooms are forwarded to a local AI endpoint (`http://localhost:11434/api/generate`) using a simple JSON POST and appended to the chat history.
