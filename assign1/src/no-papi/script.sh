#!/bin/bash

# Verifica se o número correto de argumentos foi passado
if [ "$#" -lt 2 ]; then
    echo "Uso: $0 <modes> <num_iterations> [p/n] [block_size]"
    echo "Exemplo para rodar múltiplos modos: $0 [1,2,3] 5 p 256"
    exit 1
fi

# Argumentos
MODES_RAW=$1  # Pode ser um único número ou uma lista [1,2,3]
ITER=$2       # Número de iterações
PN=$3         # "p" para paralelo, "n" para normal (apenas para modo 2)
BLOCK_SIZE=$4 # Tamanho do bloco (apenas para modo 3)

# Remover colchetes da lista de modos e converter para um array
MODES=$(echo "$MODES_RAW" | tr -d '[]' | tr ',' ' ')

# Validações
for MODE in $MODES; do
    if [[ ! "$MODE" =~ ^[1-4]$ ]]; then
        echo "Erro: O modo deve ser 1, 2, 3 ou 4."
        exit 1
    fi
done

if [[ ! "$ITER" =~ ^[1-9][0-9]*$ ]]; then
    echo "Erro: O número de iterações deve ser um valor positivo."
    exit 1
fi

if [[ "$MODES" =~ "2" ]]; then
    if [[ "$PN" != "p" && "$PN" != "n" ]]; then
        echo "Erro: O modo 2 requer 'p' para paralelo ou 'n' para normal."
        exit 1
    fi
fi

if [[ "$MODES" =~ "3" ]]; then
    if [[ "$BLOCK_SIZE" != "128" && "$BLOCK_SIZE" != "256" && "$BLOCK_SIZE" != "512" ]]; then
        echo "Erro: O modo 3 requer um block_size de 128, 256 ou 512."
        exit 1
    fi
fi

# Compilar o programa se necessário
EXECUTABLE="./multiplication"
if [ ! -f "$EXECUTABLE" ]; then
    echo "Compilando o programa..."
    g++ multiplication.cpp -o multiplication -O2 -fopenmp
fi

# Determinar o número do próximo teste e criar a pasta principal (se ainda não existir)
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

# Se algum dos modos for 4, executar todos os testes
if [ "$MODE" -eq 4 ]; then
    echo "Executando todos os testes com $ITER iterações..."

    for TEST_MODE in 1 2 3; do
        if [ "$TEST_MODE" -eq 2 ]; then
            for PARALLEL in "n" "p"; do
                TEST_DIR="$TEST_DIR" $0 $TEST_MODE $ITER $PARALLEL
            done
        elif [ "$TEST_MODE" -eq 3 ]; then
            for BLOCK in 128 256 512; do
                TEST_DIR="$TEST_DIR" $0 $TEST_MODE $ITER "" $BLOCK  # <-- CORRIGIDO: Sem "p/n"
            done
        else
            TEST_DIR="$TEST_DIR" $0 $TEST_MODE $ITER
        fi
    done
    exit 0
fi

# Executar os modos especificados
for MODE in $MODES; do

    # Define a flag para paralelismo (aplica-se apenas ao modo 2)
    PARALLEL_FLAG=0
    if [[ "$MODE" -eq 2 ]]; then
        if [ "$PN" == "p" ]; then
            PARALLEL_FLAG=1
        fi
    fi

    # Definir nome do subdiretório baseado no modo dentro da pasta do teste
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
        echo "Modo inválido!"
        exit 1
    fi

    # Criar subdiretório dentro do diretório de teste
    TEST_SUBDIR="$TEST_DIR/$SUBDIR"
    mkdir -p "$TEST_SUBDIR"

    # Lista de tamanhos de matriz
    MATRIX_SIZES=(600 1000)
  

    # Executa os testes
    for SIZE in "${MATRIX_SIZES[@]}"; do
        OUTPUT_DIR="$TEST_SUBDIR/matrix_${SIZE}"
        mkdir -p "$OUTPUT_DIR"
        OUTPUT_FILE="$OUTPUT_DIR/results.csv"

        echo "Executando mode=$MODE para matriz de tamanho $SIZE com $ITER iterações..."
        
        if [[ "$MODE" -eq 3 ]]; then
            $EXECUTABLE $MODE $SIZE $ITER $PARALLEL_FLAG $BLOCK_SIZE > "$OUTPUT_FILE"
        else
            $EXECUTABLE $MODE $SIZE $ITER $PARALLEL_FLAG > "$OUTPUT_FILE"
        fi

        mv "results_matrix_${SIZE}.csv" "$OUTPUT_FILE"
    done

    echo "Resultados salvos em $TEST_SUBDIR"

done
