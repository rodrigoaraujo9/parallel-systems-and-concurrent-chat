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

using namespace std;
using namespace chrono;

#define BKSIZE 64
#define ITERATIONS 10

using namespace std;

void writeToCSVfile(const string &filename, int matrix_size, double execution_time, bool firstEntry, bool median = false, bool avgTime = false) {
  ofstream file;
  file.open(filename, ios::app);

  if (!file.is_open()) {
    cout << "Error opening CSV file.\n";
    return;
  }

  if (firstEntry) {
    file << "Size,Time\n";
  }

  if (median) {
    file << matrix_size << ",Median," << execution_time << "\n";
  } else if (avgTime) {
    file << matrix_size << ",Average Time," << execution_time << "\n";
  }
  file.close();
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

double calculateMedian(vector<double> &times) {
  sort(times.begin(), times.end());
  int len = times.size();
  double median;
  if (len % 2 == 0) {
    median = (times[len/2-1] + times[len/2]) / 2.0;
  } else {
    median = times[len/2];
  }
  return median;
}

double calculateAvgTime(vector<double> &times) {
    size_t size = times.size();
    double sum = accumulate(times.begin(), times.end(), 0.0);
    double avgTime = sum / size;
    return avgTime;
}


void OnMultBlockWrapper(int m_ar, int m_br, double *A, double *B, double *C, int blockSize) {
  OnMultBlock(m_ar, m_br, blockSize, A, B, C);
}


void executeMultiplication(int algorithm, string filename, bool &firstEntry) {
    for (int matrix_size = 600; matrix_size <= 3000; matrix_size += 400) {
        double *A, *B, *C;
        if (posix_memalign((void **)&A, 64, matrix_size * matrix_size * sizeof(double)) != 0 || 
            posix_memalign((void **)&B, 64, matrix_size * matrix_size * sizeof(double)) != 0 ||
            posix_memalign((void **)&C, 64, matrix_size * matrix_size * sizeof(double)) != 0) {
            cout << "Memory allocation failed.\n";
            return;
        }

        generateRandomMatrix(A, matrix_size);
        generateRandomMatrix(B, matrix_size);

        vector<double>execution_times;
        int iteration = 1;

        while(iteration <= ITERATIONS) {
            double execution_time = 0.0;
            if (algorithm == 1) {
                execution_time = measureTime(OnMult, matrix_size, A, B, C);
            }
            execution_times.push_back(execution_time);
            writeToCSVfile(filename, matrix_size, execution_time, firstEntry);
            firstEntry = false;
            iteration++;
        
        }

        double median = calculateMedian(execution_times);
        double avgTime = calculateAvgTime(execution_times);
        writeToCSVfile(filename, matrix_size, median, false, true, false);
        writeToCSVfile(filename, matrix_size, avgTime, false, false, true);

        free(A);
        free(B);
        free(C);
    }
}



int main(int argc, char *argv[]) {

  if (argc < 1) {
    cout << "Usage ./mul <algorithm>\n";
    return 1;
  }

  int algorithm = atoi(argv[1]);

  if (algorithm < 1 || algorithm > 3) {
    cout << "Invalid algorithm.\n";
    return 1;
  }

  srand(time(0));
  string filename = "time.csv";
  bool firstEntry = true;

  if (algorithm == 1) {
    executeMultiplication(1, filename, firstEntry);
  } else {
    cout << "Not implemented for algorithm 2 or 3." << endl;
  }

  return 0;
}
