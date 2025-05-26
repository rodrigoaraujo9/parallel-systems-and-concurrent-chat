#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

JAVA_BIN="java"
KEY_STORE="$SCRIPT_DIR/chatserver.jks"
KEY_STORE_PASSWORD="password"
CLASSPATH="$SCRIPT_DIR/../out/production/assign2:$SCRIPT_DIR/lib/json-20231013.jar"

$JAVA_BIN -Djavax.net.ssl.keyStore="$KEY_STORE" \
          -Djavax.net.ssl.keyStorePassword="$KEY_STORE_PASSWORD" \
          -classpath "$CLASSPATH" Server
