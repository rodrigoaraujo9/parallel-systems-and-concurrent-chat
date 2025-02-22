#include <chrono>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <iostream>
#include <vector>

using namespace std;
using namespace std::chrono;

#define BKSIZE 128

using namespace std;

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

void transposeMatrix(double *matrix, double *transposed, int size) {
  for (int i = 0; i < size; i++) {
    for (int j = 0; j < size; j++) {
      transposed[j * size + i] = matrix[i * size + j];
    }
  }
}

void OnMult(int m_ar, int m_br, double *pha, double *phb, double *phc) {
  memset(phc, 0, m_ar * m_br * sizeof(double));

  vector<double> phbT(m_br * m_br);
  transposeMatrix(phb, phbT.data(), m_br);

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

void OnMultLine(int m_ar, int m_br, double *pha, double *phb, double *phc) {
  memset(phc, 0, m_ar * m_br * sizeof(double));

  vector<double> phbT(m_br * m_br);
  transposeMatrix(phb, phbT.data(), m_br);

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

  vector<double> phbT(m_br * m_br);
  transposeMatrix(phb, phbT.data(), m_br);

  for (int ii = 0; ii < m_ar; ii += bkSize) {
    for (int jj = 0; jj < m_br; jj += bkSize) {
      for (int kk = 0; kk < m_ar; kk += bkSize) {
        for (int i = ii; i < min(ii + bkSize, m_ar); i++) {
          for (int j = jj; j < min(jj + bkSize, m_br); j++) {
            double sum = phc[i * m_ar + j];
            for (int k = kk; k < min(kk + bkSize, m_ar); k++) {
              sum += pha[i * m_ar + k] * phbT[j * m_br + k];
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
                   int iterations = 5) {
  double total_time = 0.0;

  for (int i = 0; i < iterations; i++) {
    clock_t start = clock();
    multiplyFunc(size, size, A, B, C);
    clock_t end = clock();
    total_time += (double)(end - start) / CLOCKS_PER_SEC;
  }

  return total_time / iterations;
}

void OnMultBlockWrapper(int m_ar, int m_br, double *A, double *B, double *C) {
  int blockSize = BKSIZE;
  OnMultBlock(m_ar, m_br, blockSize, A, B, C);
}

int main() {
  srand(time(0));

  int sizes[] = {600, 3000};

  for (int s = 0; s < 2; s++) {
    int size = sizes[s];

    cout << "\nRunning matrix multiplication for size " << size << "x" << size
         << "...\n";

    double *A =
        static_cast<double *>(aligned_alloc(64, size * size * sizeof(double)));
    double *B =
        static_cast<double *>(aligned_alloc(64, size * size * sizeof(double)));
    double *C =
        static_cast<double *>(aligned_alloc(64, size * size * sizeof(double)));

    generateRandomMatrix(A, size);
    generateRandomMatrix(B, size);

    cout << "Warming up cache..." << endl;
    OnMultBlockWrapper(size, size, A, B, C);

    auto start = high_resolution_clock::now();
    double timeBlock = measureTime(OnMultBlockWrapper, size, A, B, C);
    auto end = high_resolution_clock::now();

    double elapsedTime = duration<double>(end - start).count();

    cout << "Average execution time (Block Multiplication): " << timeBlock
         << " seconds\n";
    cout << "Total elapsed time: " << elapsedTime
         << " seconds (including warm-up)" << endl;

    free(A);
    free(B);
    free(C);
  }

  return 0;
}
