#include <chrono>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <iostream>
#include <vector>
#include <functional>

using namespace std;
using namespace chrono;

#define BKSIZE 64

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

void transposeMatrix(double *matrix, double *transposed, int size, int blockSize = 32) {
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

  for (int i = 0; i < m_ar; i++) {
    for (int k = 0; k < m_ar; k++) {
      double temp = pha[i * m_ar + k];
      for (int j = 0; j < m_br; j++) {
        phc[i * m_ar + j] += temp * phbT[j * m_br + k];
      }
    }
  }
}

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

double measureTime(std::function<void(int, int, double *, double *, double *)> multiplyFunc, int size, double *A, double *B, double *C) {
    auto start = high_resolution_clock::now();
    multiplyFunc(size, size, A, B, C);
    auto end = high_resolution_clock::now();
    return duration<double>(end-start).count();
}

void OnMultBlockWrapper(int m_ar, int m_br, double *A, double *B, double *C, int blockSize) {
  OnMultBlock(m_ar, m_br, blockSize, A, B, C);
}

int main(int argc, char *argv[]) {

  if (argc < 3) {
    cout << "Usage ./mul <algorithm> <matrix_size> [block_size]\n";
    return 1;
  }

  int algorithm = atoi(argv[1]);
  int matrix_size = atoi(argv[2]);
  int block_size = 0;

  if (algorithm == 3) {
    if (argc != 4) {
      cout << "Usage: ./mul 3 <matrix_size> <block_size>\n";
      return 1;
    }
    block_size = atoi(argv[3]);
    if (block_size <= 0) {
      cout << "Invalid block size.\n";
      return 1;
    }
  } else {
    if (argc == 4) {
      cout << "Usage: ./mul <algorithm> <matrix_size>\n";
      return 1;
    }
  }

  if (algorithm < 1 || algorithm > 3) {
    cout << "Invalid algorithm.\n";
    return 1;
  } 

  if (matrix_size < 600) {
    cout << "Matrix too small. Minimum: 600x600\n";
    return 1;
  }

  srand(time(0));

  double *A, *B, *C;
  if (posix_memalign((void **)&A, 64, matrix_size * matrix_size * sizeof(double)) != 0 || 
      posix_memalign((void **)&B, 64, matrix_size * matrix_size * sizeof(double)) != 0 ||
      posix_memalign((void **)&C, 64, matrix_size * matrix_size * sizeof(double)) != 0) {
    cout << "Memory allocation failed.\n";
    return 1;
  }

  generateRandomMatrix(A, matrix_size);
  generateRandomMatrix(B, matrix_size);

  double execution_time = 0.0;

  switch(algorithm) {
    case 1:
      execution_time = measureTime(OnMult, matrix_size, A, B, C);
      cout << "Execution Time (Plain Matrix Multiplication): " << execution_time << " seconds\n";
      break;
    
    case 2:
      execution_time = measureTime(OnMultLine, matrix_size, A, B, C);
      cout << "Execution Time (Line-by-Line Matrix Multiplication): " << execution_time << " seconds\n";
      break;
      
    case 3:
      execution_time = measureTime([block_size](int s, int, double *a, double *b, double *c) { 
        OnMultBlockWrapper(s, s, a, b, c, block_size); }, matrix_size, A, B, C);
      cout << "Execution Time (Block Multiplication and Block Size = " << block_size << "): " << execution_time << " seconds\n";
      break;
  }

    free(A);
    free(B);
    free(C);

  return 0;

}
