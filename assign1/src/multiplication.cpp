#include <chrono>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <iostream>
#include <vector>
#include <functional>
#include <fstream>
#include <numeric>
#include <algorithm>
#include <omp.h>
#include <papi.h>

using namespace std;
using namespace chrono;

// PAPI events definition
#define NUM_EVENTS 4
int events[NUM_EVENTS] = {PAPI_L1_DCM, PAPI_L2_DCM, PAPI_L3_TCM, PAPI_DP_OPS};

// Function to initialize PAPI
void initPAPI() {
    if (PAPI_library_init(PAPI_VER_CURRENT) != PAPI_VER_CURRENT) {
        cerr << "Erro ao iniciar PAPI!" << endl;
        exit(1);
    }
}

void generateRandomMatrix(double *matrix, int size) {
  for (int i = 0; i < size * size; i++) {
      matrix[i] = (double)(rand() % 10 + 1);
  }
}

bool matrixMemoryAllocation(double *&A, double *&B, double *&C, int size) {
  A = new double[size * size];
  B = new double[size * size];
  C = new double[size * size];
  return A && B && C;
}


// Simple Matrix Multiplication
void OnMult(int m_ar, int m_br, double *pha, double *phb, double *phc) {
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


// Line Matrix Multiplication
void OnMultLine(int m_ar, int m_br, double *pha, double *phb, double *phc, bool parallel) {
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


// Line Matrix Multiplication with Parallelism
void OnMultLine_parallel(int m_ar, int m_br, double *pha, double *phb, double *phc) {
    memset(phc, 0, m_ar * m_br * sizeof(double));

    #pragma omp parallel for schedule(dynamic) 
    for (int i = 0; i < m_ar; i++) {
        for (int j = 0; j < m_br; j++) {
            double sum = 0.0;
            #pragma omp simd reduction(+:sum) // Enables SIMD for better vectorization
            for (int k = 0; k < m_ar; k++) {
                sum += pha[i * m_ar + k] * phb[j * m_br + k];
            }
            phc[i * m_ar + j] = sum;
        }
    }
}


// Block Matrix Multiplication
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

void OnMultBlockWrapper(int m_ar, int m_br, double *A, double *B, double *C, int blockSize) {
  OnMultBlock(m_ar, m_br, blockSize, A, B, C);
}


// MFLOPS
double calculateMFLOPs(int size, double execution_time) {
  return (2.0 * size * size * size) / (execution_time * 1e6);
}


void writeToCSV(const string &filename, int size, int iteration, double time, double mflops, long long l1_misses, long long l2_misses, long long l3_misses) {
  ofstream file(filename, ios::app);
  if (!file.is_open()) {
      cerr << "Erro ao abrir o arquivo CSV." << endl;
      return;
  }
  file << size << "," << iteration << "," << time << "," << mflops << "," << l1_misses << "," << l2_misses << "," << l3_misses << endl;
  file.close();
}


int main(int argc, char *argv[]) {
  if (argc < 5) {
      cerr << "Uso: ./multiplication <mode> <size> <iterations> <parallel_flag> [block_size]" << endl;
      return 1;
  }


  int mode = atoi(argv[1]);
  int size = atoi(argv[2]);
  int iterations = atoi(argv[3]);
  bool parallel = (atoi(argv[4]) == 1);
  int blockSize = (argc == 6) ? atoi(argv[5]) : -1;

  // Initialize PAPI
  initPAPI();

  string filename = "results_matrix_" + to_string(size) + ".csv";
  
  for (int iter = 1; iter <= iterations; iter++) {
      double *A, *B, *C;
      if (!matrixMemoryAllocation(A, B, C, size)) return 1;
      generateRandomMatrix(A, size);
      generateRandomMatrix(B, size);

      long long values[NUM_EVENTS] = {0};
      int EventSet = PAPI_NULL;

      PAPI_create_eventset(&EventSet);
      PAPI_add_events(EventSet, events, NUM_EVENTS);
      PAPI_start(EventSet);

      auto start = high_resolution_clock::now();

      if (mode == 1) {
          OnMult(size, size, A, B, C);
      } else if (mode == 2) {
          OnMultLine(size, size, A, B, C, parallel);
      } else if (mode == 3) {
          OnMultBlock(size, size, blockSize, A, B, C);
      }

      auto end = high_resolution_clock::now();
      PAPI_stop(EventSet, values);

      double execution_time = duration<double>(end - start).count();
      double mflops = calculateMFLOPs(size, execution_time);

      writeToCSV(filename, size, iter, execution_time, mflops, values[0], values[1], values[2]);

      delete[] A;
      delete[] B;
      delete[] C;
  }
  return 0;
}