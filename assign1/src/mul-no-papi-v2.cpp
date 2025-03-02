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
#define ITERATIONS 5

using namespace std;

// WRITE TO CSV FILE

void writeToCSVfile(const string &filename, int matrix_size, double execution_time, bool firstEntry, bool median = false, bool avgTime = false, int iteration = -1) {
  ofstream file;
  file.open(filename, ios::app);

  if (!file.is_open()) {
    cout << "Error opening CSV file.\n";
    return;
  }

  if (firstEntry) {
    file << "Matrix Size: " << matrix_size << "\n";
    file << "Iteration,Time\n";
  }

  if (iteration != -1) {
    file << iteration << ", " << execution_time << "\n";
  } else if (median) {
    file << "Median," << execution_time << "\n";
  } else if (avgTime) {
    file << "Average Time," << execution_time << "\n\n";
  }

  file.close();
}

// MATRICES

void generateRandomMatrix(double *matrix, int size) {
  for (int i = 0; i < size * size; i++) {
    matrix[i] = (double)(rand() % 10 + 1);
  }
}

bool matrixMemoryAllocation(double *&A, double *&B, double *&C, int size) {
  if (posix_memalign((void **)&A, 64, size * size * sizeof(double)) != 0 ||
      posix_memalign((void **)&B, 64, size * size * sizeof(double)) != 0 ||
      posix_memalign((void **)&C, 64, size * size * sizeof(double)) != 0) {
      cout << "Memory allocation failed.\n";
      return false;
  }
  return true;
}

// SIMPLE MATRIX MULTIPLICATION

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


// LINE-BY-LINE MATRIX MULTIPLICATION

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

void OnMultBlockWrapper(int m_ar, int m_br, double *A, double *B, double *C, int blockSize) {
  OnMultBlock(m_ar, m_br, blockSize, A, B, C);
}


// CALCULATE METRICS

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

// EXECUTE MULTIPLICATION

void executeMultiplication(int algorithm, const string &filename) {
    for (int matrix_size = 600; matrix_size <= 3000; matrix_size += 400) {

        double *A, *B, *C;
        if (!matrixMemoryAllocation(A, B, C, matrix_size)) return;

        generateRandomMatrix(A, matrix_size);
        generateRandomMatrix(B, matrix_size);

        vector<double>execution_times;
        int iteration = 1;
        bool firstEntry = true;

        while(iteration <= ITERATIONS) {
            double execution_time = 0.0;
            switch(algorithm) {
              case 1: 
                execution_time = measureTime(OnMult, matrix_size, A, B, C);
                break;
              case 2:
                execution_time = measureTime(OnMultLine, matrix_size, A, B, C);
                break;
              case 3:
                cout << "Not implemented for 3.\n";
            }
            execution_times.push_back(execution_time);
            writeToCSVfile(filename, matrix_size, execution_time, firstEntry, false, false, iteration);
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
  string filename = "time_algorithm_" + to_string(algorithm) + ".csv";

  executeMultiplication(algorithm, filename);
  return 0;
}
