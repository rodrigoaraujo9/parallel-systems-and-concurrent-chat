import matplotlib.pyplot as plt

x = [4096, 6144, 8192, 10240]

y1 = [9946221877, 33354970405, 78943479041, 155260161889]
y2 = [9179971030, 30756956389, 73271620973, 143249510058]
y3 = [8786136724, 29534657713, 70189365376, 136724571697]
y4 = [17537567235, 59148757447, 140090422090, 273811652121]

plt.figure(figsize=(8, 5))

plt.plot(x, y1, marker='o', linestyle='-', color='b', label='L1 Cache Misses (128)')
plt.plot(x, y2, marker='s', linestyle='-', color='r', label='L1 Cache Misses (256)')
plt.plot(x, y3, marker='v', linestyle='-', color='g', label='L1 Cache Misses (512)')
plt.plot(x, y4, marker='^', linestyle='-', color='m', label='L1 Cache Misses (Line)')



plt.xlabel('Matrix Size')
plt.ylabel('Cache Misses')
plt.title('L1 Cache Misses between Line and Block Multiplication Matrices in C/C++', pad=20)
plt.xticks(x) 
plt.legend()
plt.grid(True)

plt.show()
