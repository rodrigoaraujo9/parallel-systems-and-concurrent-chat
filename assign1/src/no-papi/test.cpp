#include <algorithm>
#include <chrono>
#include <cstring>
#include <iostream>

using std::cout;
using std::endl;
using std::min;

void OnMultBlock(int m_ar, int m_br, int bkSize, double *pha, double *phb,
                 double *phc) {
  memset(phc, 0, m_ar * m_br * sizeof(double));

  for (int ii = 0; ii < m_ar; ii += bkSize) {
    int i_end = min(ii + bkSize, m_ar);
    for (int kk = 0; kk < m_ar; kk += bkSize) {
      int k_end = min(kk + bkSize, m_ar);
      for (int jj = 0; jj < m_br; jj += bkSize) {
        int j_end = min(jj + bkSize, m_br);
        for (int i = ii; i < i_end; i++) {
          int i_offset = i * m_ar;
          for (int k = kk; k < k_end; k++) {
            double a_val = pha[i_offset + k];
            int k_offset = k * m_br;
            for (int j = jj; j < j_end; j++) {
              phc[i_offset + j] += a_val * phb[k_offset + j];
            }
          }
        }
      }
    }
  }
}

int main() {
  const int N = 10000;
  const int blockSize = 100;
  double *A = new double[N * N];
  double *B = new double[N * N];
  double *C = new double[N * N];
  for (long long i = 0; i < static_cast<long long>(N) * N; ++i) {
    A[i] = 1.0;
    B[i] = 1.0;
  }
  auto start = std::chrono::high_resolution_clock::now();
  OnMultBlock(N, N, blockSize, A, B, C);
  auto end = std::chrono::high_resolution_clock::now();
  std::chrono::duration<double> elapsed = end - start;
  cout << "Time for multiplication: " << elapsed.count() << " seconds" << endl;
  cout << "C[0] = " << C[0] << " (expected " << N << ")" << endl;
  delete[] A;
  delete[] B;
  delete[] C;
  return 0;
}
