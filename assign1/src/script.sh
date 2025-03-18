#!/bin/bash

# Check if PAPI is installed
if ! ldconfig -p | grep -q libpapi; then
    echo "Error: PAPI is not installed!"
    exit 1
fi

# Arguments
MODES_RAW=$1  # Can be a single number or a list [1,2,3]
ITER=$2       # Number of iterations
PN=$3         # "p1" or "p2" for parallel, "n" for normal (only for mode 2)
BLOCK_SIZE=$4 # Block size (only for mode 3)

# Remove brackets from the modes list and convert to an array
MODES=$(echo "$MODES_RAW" | tr -d '[]' | tr ',' ' ')

# Validations
for MODE in $MODES; do
    if [[ ! "$MODE" =~ ^[1-4]$ ]]; then
        echo "Error: Mode must be 1, 2, 3, or 4."
        exit 1
    fi
done

if [[ ! "$ITER" =~ ^[1-9][0-9]*$ ]]; then
    echo "Error: Number of iterations must be a positive value."
    exit 1
fi

if [[ "$MODES" =~ "2" ]]; then
    if [[ "$PN" != "p1" && "$PN" != "p2" && "$PN" != "n" ]]; then
        echo "Error: Mode 2 requires 'pi' or 'po' for parallel or 'n' for normal."
        exit 1
    fi
fi

if [[ "$MODES" =~ "3" ]]; then
    if [[ "$BLOCK_SIZE" != "128" && "$BLOCK_SIZE" != "256" && "$BLOCK_SIZE" != "512" ]]; then
        echo "Error: Mode 3 requires a block_size of 128, 256, or 512."
        exit 1
    fi
fi

# Compile the program if necessary
EXECUTABLE="./multiplication"
if [ ! -f "$EXECUTABLE" ]; then
    echo "Compiling the program..."
    g++ multiplication.cpp -o multiplication -O2 -fopenmp -lpapi
fi

# Determine the next test number and create the main test directory if it doesn't exist
if [ -z "$TEST_DIR" ]; then
    TEST_PREFIX="test_"
    LAST_TEST=$(ls -d ${TEST_PREFIX}* 2>/dev/null | awk -F '_' '{print $2}' | sort -n | tail -1)
    if [ -z "$LAST_TEST" ]; then
        TEST_NUMBER=0
    else
        TEST_NUMBER=$((LAST_TEST + 1))
    fi
    TEST_DIR="${TEST_PREFIX}${TEST_NUMBER}"
    mkdir -p "$TEST_DIR"
fi

# If mode is 4, execute all tests
if [ "$MODE" -eq 4 ]; then
    echo "Executing all tests with $ITER iterations..."
    for TEST_MODE in 1 2 3; do
        if [ "$TEST_MODE" -eq 2 ]; then
            for PARALLEL in "n" "pi" "po"; do
                TEST_DIR="$TEST_DIR" $0 $TEST_MODE $ITER $PARALLEL_TYPE
            done
        elif [ "$TEST_MODE" -eq 3 ]; then
            for BLOCK in 128 256 512; do
                TEST_DIR="$TEST_DIR" $0 $TEST_MODE $ITER "" $BLOCK
            done
        else
            TEST_DIR="$TEST_DIR" $0 $TEST_MODE $ITER
        fi
    done
    exit 0
fi

# Execute the specified modes
for MODE in $MODES; do

    # Define the parallel flag (applies only to mode 2)
    PARALLEL_FLAG=0
    if [[ "$MODE" -eq 2 ]]; then
        if [ "$PN" == "p1" ]; then
            PARALLEL_FLAG=1
        elif ["$PN" == "p2"]; then 
            PARALLEL_FLAG=2
        fi
    fi

    # Define subdirectory name based on the mode
    if [[ "$MODE" -eq 1 ]]; then
        SUBDIR="normal"
    elif [[ "$MODE" -eq 2 ]]; then
        if [ "$PARALLEL_FLAG" -eq 1 ]; then
            SUBDIR="line_parallel_l1"
        elif [ "$PARALLEL_FLAG" -eq 2 ]; then
            SUBDIR="line_parallel_l1"
        else
            SUBDIR="line_normal"
        fi
    elif [[ "$MODE" -eq 3 ]]; then
        SUBDIR="block_${BLOCK_SIZE}"
    else
        echo "Invalid mode!"
        exit 1
    fi

    # Create subdirectory within the test directory
    TEST_SUBDIR="$TEST_DIR/$SUBDIR"
    mkdir -p "$TEST_SUBDIR"

    # List of matrix sizes
    MATRIX_SIZES=(600 1000 1400 1800 2200 2600 3000)
    if [ "$MODE" -eq 2 ] || [ "$MODE" -eq 3 ]; then
        MATRIX_SIZES+=(4096 6144 8192 10240)
    fi

    # Execute the tests
    for SIZE in "${MATRIX_SIZES[@]}"; do
        OUTPUT_DIR="$TEST_SUBDIR/matrix_${SIZE}"
        mkdir -p "$OUTPUT_DIR"
        OUTPUT_FILE="$OUTPUT_DIR/results.csv"

        echo "Executing mode=$MODE for matrix size $SIZE with $ITER iterations..."
        # Remove previous output file, if it exists
        rm -f "$OUTPUT_FILE"

        # For each iteration, run the executable with dummy iteration parameter = 1
        for ((i=1; i<=ITER; i++)); do
            echo "Iteration $i..."
            if [[ "$MODE" -eq 3 ]]; then
                if [ $i -eq 1 ]; then
                    $EXECUTABLE $MODE $SIZE 1 $PARALLEL_FLAG $BLOCK_SIZE > temp_results.csv
                else
                    $EXECUTABLE $MODE $SIZE 1 $PARALLEL_FLAG $BLOCK_SIZE | tail -n +2 >> temp_results.csv
                fi
            else
                if [ $i -eq 1 ]; then
                    $EXECUTABLE $MODE $SIZE 1 $PARALLEL_FLAG > temp_results.csv
                else
                    $EXECUTABLE $MODE $SIZE 1 $PARALLEL_FLAG | tail -n +2 >> temp_results.csv
                fi
            fi
        done
        mv temp_results.csv "$OUTPUT_FILE"
        echo "Results saved in $OUTPUT_FILE"
    done

done
