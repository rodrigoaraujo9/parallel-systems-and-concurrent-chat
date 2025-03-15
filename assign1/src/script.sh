#!/bin/bash

# Verify if PAPI is installed
if ! ldconfig -p | grep -q libpapi; then
    echo "Error: PAPI not installed."
    exit 1
fi



# Argumentos
MODES_RAW=$1  
ITER=$2 # Iterations
PN=$3 # For algorithm 2, choose between "p" (parallel) or "n" (normal)
BLOCK_SIZE=$(echo "$BLOCK_SIZE" | xargs)

MODES=$(echo "$MODES_RAW" | tr -d '[]' | tr ',' ' ')

# Validate
for MODE in $MODES; do
    if [[ ! "$MODE" =~ ^[1-4]$ ]]; then
        echo "Error: Please choose a number between 1 and 4."
        exit 1
    fi
done

if [[ ! "$ITER" =~ ^[1-9][0-9]*$ ]]; then
    echo "Error: The number of iterations must be a positive value."
    exit 1
fi

if [[ "$MODES" =~ "2" ]]; then
    if [[ "$PN" != "p" && "$PN" != "n" ]]; then
        echo "Error: Algorithm 2 requires "p" for parallel or "n" for normal."
        exit 1
    fi
fi

if [[ "$MODES" =~ "3" ]]; then
    if [[ "$BLOCK_SIZE" != "128" && "$BLOCK_SIZE" != "256" && "$BLOCK_SIZE" != "512" ]]; then
        echo "Error: Algorithm 3 requires a block size of 128, 256 or 512."
        exit 1
    fi
fi

# Compile program
EXECUTABLE="./multiplication"
if [ ! -f "$EXECUTABLE" ]; then
    echo "Compiling program..."
    g++ multiplication.cpp -o multiplication -O2 -fopenmp -lpapi
fi


# Create folder for next test
if [ -z "$TEST_DIR" ]; then
    TEST_PREFIX="teste_"
    LAST_TEST=$(ls -d ${TEST_PREFIX}* 2>/dev/null | awk -F '_' '{print $2}' | sort -n | tail -1)
    if [ -z "$LAST_TEST" ]; then
        TEST_NUMBER=0
    else
        TEST_NUMBER=$((LAST_TEST + 1))
    fi
    TEST_DIR="${TEST_PREFIX}${TEST_NUMBER}"
    mkdir -p "$TEST_DIR"
fi

# Execute all tests
if [ "$MODE" -eq 4 ]; then
    echo "Executing all tests with $ITER iterations..."

    for TEST_MODE in 1 2 3; do
        if [ "$TEST_MODE" -eq 2 ]; then
            for PARALLEL in "n" "p"; do
                TEST_DIR="$TEST_DIR" $0 $TEST_MODE $ITER $PARALLEL
            done
        elif [ "$TEST_MODE" -eq 3 ]; then
            for BLOCK in 128 256 512; do
                TEST_DIR="$TEST_DIR" $0 $TEST_MODE $ITER $BLOCK 
            done
        else
            TEST_DIR="$TEST_DIR" $0 $TEST_MODE $ITER
        fi
    done
    exit 0
fi

# Execute specified algorithm
for MODE in $MODES; do

    # Flag for parallelism in algorithm 2
    PARALLEL_FLAG=0
    if [[ "$MODE" -eq 2 ]]; then
        if [ "$PN" == "p" ]; then
            PARALLEL_FLAG=1
        fi
    fi

    # Subfolder name
    if [[ "$MODE" -eq 1 ]]; then
        SUBDIR="normal"
    elif [[ "$MODE" -eq 2 ]]; then
        if [ "$PARALLEL_FLAG" -eq 1 ]; then
            SUBDIR="line_parallel"
        else
            SUBDIR="line_normal"
        fi
    elif [[ "$MODE" -eq 3 ]]; then
        SUBDIR="block_${BLOCK_SIZE}"
    else
        echo "Invalid mode"
        exit 1
    fi

    # Create subfolder
    TEST_SUBDIR="$TEST_DIR/$SUBDIR"
    mkdir -p "$TEST_SUBDIR"

    # Matrix sizes
    MATRIX_SIZES=(600 1000 1400 1800 2200 2600 3000)
    if [ "$MODE" -eq 2 ] || [ "$MODE" -eq 3 ]; then
        MATRIX_SIZES+=(4096 6144 8192 10240)
    fi

    # Execute tests
    for SIZE in "${MATRIX_SIZES[@]}"; do
        OUTPUT_DIR="$TEST_SUBDIR/matrix_${SIZE}"
        mkdir -p "$OUTPUT_DIR"
        OUTPUT_FILE="$OUTPUT_DIR/results.csv"

        echo "Executing algorithm $MODE for matrix size $SIZE with $ITER iterations..."
        
        if [[ "$MODE" -eq 3 ]]; then
            $EXECUTABLE $MODE $SIZE $ITER $BLOCK_SIZE > "$OUTPUT_FILE"
        else
            $EXECUTABLE $MODE $SIZE $ITER $PARALLEL_FLAG > "$OUTPUT_FILE"
        fi

        mv "results_matrix_${SIZE}.csv" "$OUTPUT_FILE"
    done

    echo "Results saved in $TEST_SUBDIR"

done
