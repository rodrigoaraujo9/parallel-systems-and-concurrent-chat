#!/bin/bash
set -euo pipefail

# Check if the number of iterations is provided as the first parameter.
if [ $# -lt 1 ]; then
    echo "Usage: $0 <number_of_iterations>"
    exit 1
fi

NUM_RUNS="$1"
shift

# Determine the directory of this script and the project root.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Build the project in release mode from the project root.
cd "$PROJECT_ROOT"
cargo build --release

# Try to locate the binary with possible names.
BINARY1="$PROJECT_ROOT/target/release/matrix_mult"
BINARY2="$PROJECT_ROOT/target/release/mul-rust"
if [ -f "$BINARY1" ]; then
    BINARY="$BINARY1"
elif [ -f "$BINARY2" ]; then
    BINARY="$BINARY2"
else
    echo "Error: Binary not found at $BINARY1 or $BINARY2"
    exit 1
fi

# Create a new results folder with incremental naming.
result_index=0
while [ -d "$SCRIPT_DIR/results_$result_index" ]; do
    result_index=$((result_index + 1))
done
RESULTS_DIR="$SCRIPT_DIR/results_$result_index"
mkdir -p "$RESULTS_DIR"

# For each mode (Normal and Line) produce one CSV file bundling all iterations.
for mode in n l; do
    if [ "$mode" = "n" ]; then
        CSV_FILE="$RESULTS_DIR/normal.csv"
    else
        CSV_FILE="$RESULTS_DIR/line.csv"
    fi

    # Write header: matrix_size, then one column per iteration.
    header="matrix_size"
    for r in $(seq 1 "$NUM_RUNS"); do
        header="$header,run$r"
    done
    echo "$header" > "$CSV_FILE"

    # Loop through matrix sizes from 600 to 3000 (step of 400).
    for size in $(seq 600 400 3000); do
        line="$size"
        # Run the test NUM_RUNS times for this matrix size.
        for r in $(seq 1 "$NUM_RUNS"); do
            # The binary prints a header in the first line and the elapsed time in the second.
            time_sec=$("$BINARY" "$mode" "$size" | sed -n '2p')
            line="$line,$time_sec"
        done
        echo "$line" >> "$CSV_FILE"
    done
done

echo "All tests completed. Results are saved in $RESULTS_DIR."
