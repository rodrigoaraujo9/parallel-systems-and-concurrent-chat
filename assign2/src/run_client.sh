#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

JAVA_BIN="java"

TRUST_STORE="$SCRIPT_DIR/chatserver.jks"
TRUST_STORE_PASSWORD="password"

CLASSPATH="$SCRIPT_DIR/../out/production/assign2:$SCRIPT_DIR/lib/json-20231013.jar"

cd "$SCRIPT_DIR" || exit 1

$JAVA_BIN -Djavax.net.ssl.trustStore="$TRUST_STORE" \
          -Djavax.net.ssl.trustStorePassword="$TRUST_STORE_PASSWORD" \
          -classpath "$CLASSPATH" Client
