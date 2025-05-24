#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

SRC_DIR="$SCRIPT_DIR"
OUT_DIR="$PROJECT_ROOT/out/production/assign2"
LIB_JAR="$SRC_DIR/lib/json-20231013.jar"

rm -rf "$OUT_DIR"

mkdir -p "$OUT_DIR"

echo "Compiling..."
javac -d "$OUT_DIR" -cp "$LIB_JAR" "$SRC_DIR"/*.java

if [ $? -eq 0 ]; then
    echo "Compilation successful. Classes are in $OUT_DIR"
else
    echo "Compilation failed."
fi
