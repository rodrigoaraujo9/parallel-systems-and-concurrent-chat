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
#include <papi.h>

using namespace std;
using namespace chrono;

#define BKSIZE 64
#define ITERATIONS 5

using namespace std;

// WRITE TO CSV FILE

void writeToCSVfile(const string &filename, int matrix_size, double execution_time, long long l1_misses, long long l2_misses, bool firstEntry, bool median = false, bool avgTime = false, int iteration = -1) {
  ofstream file;
  file.open(filename, ios::app);

  if (!file.is_open()) {
    cout << "Error opening CSV file.\n";
    return;
  }

  if (firstEntry) {
    file << "Matrix Size,Iteration,Time,L1 Misses,L2 Misses\n";
  }

  if (iteration != -1) {
    file << matrix_size << "," << iteration << "," << execution_time << "," << l1_misses << "," << l2_misses << "\n";
  } else if (median) {
    file << matrix_size << ",Median," << execution_time << ",,\n";
  } else if (avgTime) {
    file << matrix_size << ",Average Time," << execution_time << ",,\n";
  }

  file.close();
}

// Matrices 

bool matrixMemoryAllocation(double *&A, double *&B, double *&C, int size) {
  if (posix_memalign((void **)&A, 64, size * size * sizeof(double)) != 0 ||
      posix_memalign((void **)&B, 64, size * size * sizeof(double)) != 0 ||
      posix_memalign((void **)&C, 64, size * size * sizeof(double)) != 0) {
      cout << "Memory allocation failed.\n";
      return false;
  }
  return true;
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


// LINE-BY-LINE MATRIX MULTIPLICATION 

void OnMultLine(int m_ar, int m_br, double *pha, double *phb, double *phc) {
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

// PAPI INITIALIZATION
void initPAPI() {
    int retval = PAPI_library_init(PAPI_VER_CURRENT);
    if (retval != PAPI_VER_CURRENT) {
        cerr << "Erro ao iniciar PAPI: " << PAPI_strerror(retval) 
             << " (Error Code: " << retval << ")" << endl;
        cerr << "Check if PAPI is installed correctly and linked during compilation.\n";
        exit(1);
    }

    // Initialize thread support
    retval = PAPI_thread_init((unsigned long (*)(void))pthread_self);
    if (retval != PAPI_OK) {
        cerr << "PAPI_thread_init failed: " << PAPI_strerror(retval) << endl;
        exit(1);
    }
}



// EXECUTE MULTIPLICATION WITH PAPI METRICS

void executeMultiplication(int algorithm, const string &filename) {
  vector<int> matrix_sizes = {600, 1000, 1400, 1800, 2200, 2600, 3000};

  if (algorithm == 2) 
    matrix_sizes.insert(matrix_sizes.end(), {4096, 6144, 8192, 10240});

  for (int matrix_size : matrix_sizes) {
    double *A, *B, *C;
    if (!matrixMemoryAllocation(A, B, C, matrix_size)) return;

    generateRandomMatrix(A, matrix_size);
    generateRandomMatrix(B, matrix_size);

    vector<double> execution_times;
    bool firstEntry = true;

    int EventSet = PAPI_NULL;
    long long values[2];

    PAPI_create_eventset(&EventSet);
    PAPI_add_event(EventSet, PAPI_L1_DCM);
    PAPI_add_event(EventSet, PAPI_L2_DCM);

    for (int iteration = 1; iteration <= ITERATIONS; iteration++) {
        PAPI_start(EventSet);
        auto start = high_resolution_clock::now();

        double execution_time = 0.0;
        switch(algorithm) {
          case 2:
            execution_time = measureTime(OnMultLine, matrix_size, A, B, C);
            break;
          case 3:
            cout << "Not implemented for 3.\n";
        }
        auto end = high_resolution_clock::now();
        execution_time = duration<double>(end - start).count();
        PAPI_stop(EventSet, values);

        execution_times.push_back(execution_time);
        writeToCSVfile(filename, matrix_size, execution_time, values[0], values[1], firstEntry, false, false, iteration);
        firstEntry = false;
    }

    double median = calculateMedian(execution_times);
    double avgTime = calculateAvgTime(execution_times);

    writeToCSVfile(filename, matrix_size, median, 0, 0, false, true, false);
    writeToCSVfile(filename, matrix_size, avgTime, 0, 0, false, false, true);

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
  initPAPI();
  string filename = "time_algorithm_" + to_string(algorithm) + ".csv";

  executeMultiplication(algorithm, filename);
  return 0;
}