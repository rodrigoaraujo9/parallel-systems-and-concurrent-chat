import matplotlib.pyplot as plt

x = [4096, 6144, 8192, 10240]

y1 = [137438953472, 463856467968, 1099511627776, 2147483648000]
y2 = [137438953472, 463856467968, 1099511627776, 2147483648000]
y3 = [137438953472, 463856467968, 1099511627776, 2147483648000]
y4 = [137438953472, 463856467968, 1099511627776, 2147483648000]

plt.figure(figsize=(8, 5))

plt.plot(x, y1, marker='o', linestyle='-', color='b', label='Block (128)')
plt.plot(x, y2, marker='s', linestyle='-', color='r', label='Block (256)')
plt.plot(x, y3, marker='v', linestyle='-', color='g', label='Block (512)')
plt.plot(x, y4, marker='^', linestyle='-', color='m', label='Line')


plt.xlabel('Matrix Size')
plt.ylabel('GFLOPS')
plt.title('GFLOPS comparison between Line and Block Multiplication Matrices in C/C++', pad=20)
plt.xticks(x) 
plt.legend()
plt.grid(True)

plt.show()
