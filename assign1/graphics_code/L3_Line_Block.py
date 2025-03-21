import matplotlib.pyplot as plt

x = [4096, 6144, 8192, 10240]

y1 = [3071312200, 9447342138, 34792130726, 56765248032]
y2 = [1861640986, 7204143558, 83152492773, 36566004199]
y3 = [4190202899, 2716319021, 72692183430, 19267144701]
y4 = [9272725880, 32352362751, 77493076097, 152658434064]

plt.figure(figsize=(8, 5))

plt.plot(x, y1, marker='o', linestyle='-', color='b', label='L3 Cache Misses (128)')
plt.plot(x, y2, marker='s', linestyle='-', color='r', label='L3 Cache Misses (256)')
plt.plot(x, y3, marker='v', linestyle='-', color='g', label='L3 Cache Misses (512)')
plt.plot(x, y4, marker='^', linestyle='-', color='m', label='L3 Cache Misses (Line)')



plt.xlabel('Matrix Size')
plt.ylabel('Cache Misses')
plt.title('L3 Cache Misses between Line and Block Multiplication Matrices in C/C++', pad=20)
plt.xticks(x) 
plt.legend()
plt.grid(True)

plt.show()
