#include <chrono>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <iostream>
#include <vector>

using namespace std;
using namespace chrono;

#define BKSIZE 64

using namespace std;

void transposeMatrix(double *matrix, double *transposed, int size,
                     int blockSize = 32) {
#pragma omp parallel for
  for (int i = 0; i < size; i += blockSize) {
    for (int j = 0; j < size; j += blockSize) {
      for (int bi = i; bi < min(i + blockSize, size); ++bi) {
        for (int bj = j; bj < min(j + blockSize, size); ++bj) {
          transposed[bj * size + bi] = matrix[bi * size + bj];
        }
      }
    }
  }
}

void OnMultLineImproved(int m_ar, int m_br, double *pha, double *phb,
                        double *phc) {
  memset(phc, 0, m_ar * m_br * sizeof(double));

  vector<double> phbT(m_br * m_br);
  transposeMatrix(phb, phbT.data(), m_br);

#pragma omp parallel for collapse(2) schedule(dynamic)
  for (int i = 0; i < m_ar; i++) {
    for (int j = 0; j < m_br; j++) {
      double sum = 0.0;
      for (int k = 0; k < m_ar; k++) {
        sum += pha[i * m_ar + k] * phbT[j * m_br + k];
      }
      phc[i * m_ar + j] = sum;
    }
  }
}

void OnMultBlockImproved(int m_ar, int m_br, int bkSize, double *pha,
                         double *phb, double *phc) {
  memset(phc, 0, m_ar * m_br * sizeof(double));

#pragma omp parallel for collapse(2) schedule(dynamic)
  for (int ii = 0; ii < m_ar; ii += bkSize) {
    for (int jj = 0; jj < m_br; jj += bkSize) {
      for (int kk = 0; kk < m_ar; kk += bkSize) {
        int iMax = min(ii + bkSize, m_ar);
        int jMax = min(jj + bkSize, m_br);
        int kMax = min(kk + bkSize, m_ar);
        for (int i = ii; i < iMax; i++) {
          for (int k = kk; k < kMax; k++) {
            double a_val = pha[i * m_ar + k];
            for (int j = jj; j < jMax; j++) {
              phc[i * m_ar + j] += a_val * phb[k * m_br + j];
            }
          }
        }
      }
    }
  }
}

void OnMultBlockWrapperImproved(int m_ar, int m_br, double *A, double *B,
                                double *C) {
  int blockSize = BKSIZE;
  OnMultBlockImproved(m_ar, m_br, blockSize, A, B, C);
}

void generateRandomMatrix(double *matrix, int size) {
  for (int i = 0; i < size * size; i++) {
    matrix[i] = (double)(rand() % 10 + 1);
  }
}

void printMatrix(double *matrix, int size) {
  for (int i = 0; i < min(size, 10); i++) {
    for (int j = 0; j < min(size, 10); j++) {
      cout << matrix[i * size + j] << " ";
    }
    cout << endl;
  }
  cout << "..." << endl;
}

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

void OnMultLine(int m_ar, int m_br, double *pha, double *phb, double *phc) {
  memset(phc, 0, m_ar * m_br * sizeof(double));

  vector<double> phbT(m_br * m_br);
  transposeMatrix(phb, phbT.data(), m_br);
  // #pragma omp parallel for
  for (int i = 0; i < m_ar; i++) {
    for (int k = 0; k < m_ar; k++) {
      double temp = pha[i * m_ar + k];
      for (int j = 0; j < m_br; j++) {
        phc[i * m_ar + j] += temp * phbT[j * m_br + k];
      }
    }
  }
}

void OnMultBlock(int m_ar, int m_br, int bkSize, double *pha, double *phb,
                 double *phc) {
  memset(phc, 0, m_ar * m_br * sizeof(double));
#pragma omp parallel for collapse(2)
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

double measureTime(void (*multiplyFunc)(int, int, double *, double *, double *),
                   int size, double *A, double *B, double *C,
                   int iterations = 1) {
  double total_time = 0.0;

  for (int i = 0; i < iterations; i++) {
    auto start = high_resolution_clock::now();
    multiplyFunc(size, size, A, B, C);
    auto end = high_resolution_clock::now();

    total_time += duration<double>(end - start).count();
  }

  return total_time / iterations;
}

void OnMultBlockWrapper(int m_ar, int m_br, double *A, double *B, double *C) {
  int blockSize = BKSIZE;
  OnMultBlockImproved(m_ar, m_br, blockSize, A, B, C);
}

int main() {
  srand(time(0));

  int sizes[] = {600, 1000, 1400, 1800, 2200, 2600, 3000};

  for (int size : sizes) {
    cout << "\nRunning matrix multiplication for size " << size << "x" << size
         << "...\n";

    double *A, *B, *C;
    posix_memalign((void **)&A, 64, size * size * sizeof(double));
    posix_memalign((void **)&B, 64, size * size * sizeof(double));
    posix_memalign((void **)&C, 64, size * size * sizeof(double));

    generateRandomMatrix(A, size);
    generateRandomMatrix(B, size);

    cout << "Warming up cache...\n";
    OnMultBlockWrapper(size, size, A, B, C);

    double timeBlock = measureTime(OnMultBlockWrapper, size, A, B, C);

    cout << "Avg Execution Time (Block Multiplication): " << timeBlock
         << " seconds\n";

    free(A);
    free(B);
    free(C);
  }

  return 0;
}
