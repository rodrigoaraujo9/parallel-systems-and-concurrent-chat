#include <chrono>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <iostream>
#include <algorithm>
#include <omp.h>

using namespace std;
using namespace chrono;

// Function to generate a random matrix with values between 1 and 10.
void generateRandomMatrix(double *matrix, int size) {
    for (int i = 0; i < size * size; i++) {
        matrix[i] = (double)(rand() % 10 + 1);
    }
}

// Function to allocate memory for three matrices A, B, and C.
bool matrixMemoryAllocation(double *&A, double *&B, double *&C, int size) {
    A = new double[size * size];
    B = new double[size * size];
    C = new double[size * size];
    return A && B && C;
}

// SIMPLE MATRIX MULTIPLICATION
void OnMult(int m_ar, int m_br, double *pha, double *phb, double *phc) {
    // Set all elements of the result matrix to zero.
    memset(phc, 0, m_ar * m_br * sizeof(double));
    for (int i = 0; i < m_ar; i++) {
        for (int j = 0; j < m_br; j++) {
            double sum = 0.0;
            for (int k = 0; k < m_ar; k++) {
                sum += pha[i * m_ar + k] * phb[k * m_br + j];
            }
            phc[i * m_ar + j] = sum;
        }
    }
}

// LINE-BY-LINE MATRIX MULTIPLICATION
void OnMultLine(int m_ar, int m_br, double *pha, double *phb, double *phc) {
    memset(phc, 0, m_ar * m_br * sizeof(double));
    for (int i = 0; i < m_ar; i++) {
        for (int j = 0; j < m_br; j++) {
            double temp = pha[i * m_ar + j];
            for (int k = 0; k < m_ar; k++) {
                phc[i * m_ar + k] += temp * phb[j * m_br + k];
            }
        }
    }
}

// LINE-BY-LINE PARALLEL MATRIX MULTIPLICATION
void OnMultLine_parallel(int m_ar, int m_br, double *pha, double *phb, double *phc) {
    memset(phc, 0, m_ar * m_br * sizeof(double));
    #pragma omp parallel for schedule(dynamic)
    for (int i = 0; i < m_ar; i++) {
        for (int j = 0; j < m_br; j++) {
            double sum = 0.0;
            #pragma omp simd reduction(+:sum)
            for (int k = 0; k < m_ar; k++) {
                sum += pha[i * m_ar + k] * phb[j * m_br + k];
            }
            phc[i * m_ar + j] = sum;
        }
    }
}

// BLOCK MATRIX MULTIPLICATION
void OnMultBlock(int m_ar, int m_br, int bkSize, double *pha, double *phb, double *phc) {
    memset(phc, 0, m_ar * m_br * sizeof(double));
    for (int ii = 0; ii < m_ar; ii += bkSize) {
        for (int jj = 0; jj < m_br; jj += bkSize) {
            for (int kk = 0; kk < m_ar; kk += bkSize) {
                for (int i = ii; i < min(ii + bkSize, m_ar); i++) {
                    for (int j = jj; j < min(jj + bkSize, m_br); j++) {
                        double sum = phc[i * m_ar + j];
                        for (int k = kk; k < min(kk + bkSize, m_ar); k++) {
                            sum += pha[i * m_ar + k] * phb[k * m_br + j];
                        }
                        phc[i * m_ar + j] = sum;
                    }
                }
            }
        }
    }
}

int main(int argc, char *argv[]) {
    if (argc < 5) {
        cout << "Usage: ./multiplication <mode> <matrix_size> <dummy_iteration> <parallel_flag> [block_size]" << endl;
        return 1;
    }

    int mode = atoi(argv[1]);
    int matrix_size = atoi(argv[2]);
    // The iteration parameter is now ignored; each execution performs only one iteration.
    bool parallel = (atoi(argv[4]) == 1);
    int block_size = (argc == 6) ? atoi(argv[5]) : -1;

    double *A, *B, *C;
    if (!matrixMemoryAllocation(A, B, C, matrix_size)) return 1;
    generateRandomMatrix(A, matrix_size);
    generateRandomMatrix(B, matrix_size);

    auto start = high_resolution_clock::now();
    if (mode == 1) {
        OnMult(matrix_size, matrix_size, A, B, C);
    } else if (mode == 2) {
        if (parallel) {
            OnMultLine_parallel(matrix_size, matrix_size, A, B, C);
        } else {
            OnMultLine(matrix_size, matrix_size, A, B, C);
        }
    } else if (mode == 3) {
        OnMultBlock(matrix_size, matrix_size, block_size, A, B, C);
    }
    auto end = high_resolution_clock::now();
    double execution_time = duration<double>(end - start).count();

    // Print the results in CSV format to stdout.
    cout << "Matrix Size,Time" << endl;
    cout << matrix_size << "," << execution_time << endl;

    delete[] A;
    delete[] B;
    delete[] C;

    return 0;
}
