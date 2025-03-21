import matplotlib.pyplot as plt

x = [4096, 6144, 8192, 10240]

y1 = [33457596583, 112233435221, 267007224926, 512416017441]
y2 = [23193241225, 75251128816, 160392715619, 348131898248]
y3 = [19087905940, 67333973864, 134912731960, 306253484567]
y4 = [16053976157, 53982060421, 127645852606, 249637990728]

plt.figure(figsize=(8, 5))

plt.plot(x, y1, marker='o', linestyle='-', color='b', label='L2 Cache Misses (128)')
plt.plot(x, y2, marker='s', linestyle='-', color='r', label='L2 Cache Misses (256)')
plt.plot(x, y3, marker='v', linestyle='-', color='g', label='L2 Cache Misses (512)')
plt.plot(x, y4, marker='^', linestyle='-', color='m', label='L2 Cache Misses (Line)')



plt.xlabel('Matrix Size')
plt.ylabel('Cache Misses')
plt.title('L2 Cache Misses between Line and Block Multiplication Matrices in C/C++', pad=20)
plt.xticks(x) 
plt.legend()
plt.grid(True)

plt.show()
