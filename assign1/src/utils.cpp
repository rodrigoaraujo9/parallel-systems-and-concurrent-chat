#include <cmath>
#include <cstddef>
#include <iostream>
#include <string>
#include <vector>
using namespace std;
// cache size in bytes, fraction 0<f<=1
namespace block_utils {
struct CacheInfo {
  std::string name;
  size_t size;
};

int calcOptimalBlockSize(size_t cacheSizeBytes, double usageFraction = 0.8) {
  if (usageFraction <= 0.0 || usageFraction > 1.0) {
    return -1;
  }
  double effective_cache = cacheSizeBytes * usageFraction;
  double b_raw = sqrt(effective_cache / 24);
  if (b_raw < 8.0)
    return -1;
  int b_aligned = static_cast<int>(floor(b_raw) / 8) * 8;
  if (b_aligned < 8)
    return -1;
  return b_aligned;
}

int calcOptimalBlockSizeForCache(const vector<CacheInfo> &caches,
                                 double usageFraction = 0.8) {
  for (const auto &cache : caches) {
    int blockSize = calcOptimalBlockSize(cache.size, usageFraction);
    if (blockSize != -1) {
      cout << "Using cache \"" << cache.name << "\" (" << cache.size
           << " bytes) yields a block size of " << blockSize << std::endl;
      return blockSize;
    }
  }
  return -1;
}

int calcMinOptimalBlockSizeForCaches(const vector<CacheInfo> &caches,
                                     double usageFraction = 0.8) {
  int minBlockSize = numeric_limits<int>::max();
  bool found = false;
  for (const auto &cache : caches) {
    int blockSize = calcOptimalBlockSize(cache.size, usageFraction);
    if (blockSize != -1) {
      if (blockSize < minBlockSize) {
        minBlockSize = blockSize;
      }
      found = true;
    }
  }
  return found ? minBlockSize : -1;
}
} // namespace block_utils

// test

/*
int main() {
  // Test 1: Typical L1 cache size of 128 KB and usageFraction = 0.8
  size_t cacheSize1 = 128 * 1024; // 131072 bytes
  int blockSize1 = calcOptimalBlockSize(cacheSize1, 0.8);
  cout << "Test 1: Cache Size = " << cacheSize1
       << " bytes, usageFraction = 0.8, block size = " << blockSize1 <<
endl;

  // Test 2: Invalid usageFraction (>1)
  int blockSize2 = calcOptimalBlockSize(cacheSize1, 1.5);
  cout << "Test 2: Cache Size = " << cacheSize1
       << " bytes, usageFraction = 1.5, block size = " << blockSize2 <<
endl;

  // Test 3: Smaller cache, e.g., 64 KB, usageFraction = 0.8
  size_t cacheSize3 = 64 * 1024; // 65536 bytes
  int blockSize3 = calcOptimalBlockSize(cacheSize3, 0.8);
  cout << "Test 3: Cache Size = " << cacheSize3
       << " bytes, usageFraction = 0.8, block size = " << blockSize3 <<
endl;

  // Test 4: Extremely small cache (should return -1 if below minimum
  // requirements)
  size_t cacheSize4 = 16 * 1024; // 16384 bytes
  int blockSize4 = calcOptimalBlockSize(cacheSize4, 0.8);
  cout << "Test 4: Cache Size = " << cacheSize4
       << " bytes, usageFraction = 0.8, block size = " << blockSize4 <<
endl;

  // Test 5: Usage fraction equal to 0 (invalid)
  int blockSize5 = calcOptimalBlockSize(cacheSize1, 0.0);
  cout << "Test 5: Cache Size = " << cacheSize1
       << " bytes, usageFraction = 0.0, block size = " << blockSize5 <<
endl;

  return 0;
}

*/
