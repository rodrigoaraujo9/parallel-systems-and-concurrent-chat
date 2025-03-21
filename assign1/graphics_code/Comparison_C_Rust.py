import matplotlib.pyplot as plt

x = [600, 1000, 1400, 1800, 2200, 2600, 3000]

y1 = [0.3943890, 1.9190000, 5.3654500, 13.4118000, 21.3099000, 35.5255500, 56.0100000]
y2 = [0.0530064, 0.2485185, 0.679731, 1.4390250, 2.6232450, 5.3655350, 7.76897]

y3 = [0.0546793335, 0.2571244165, 0.6969611665, 1.73367825, 3.365580896, 5.563265125, 8.612678292]
y4 = [0.059070271, 0.2557649585, 0.697282833, 1.725798729, 3.364768958, 5.569807771, 8.570022084]

plt.figure(figsize=(8, 5))

plt.plot(x, y1, marker='o', linestyle='-', color='b', label='Simple C++')
plt.plot(x, y2, marker='s', linestyle='-', color='r', label='Line C++')

plt.plot(x, y3, marker='^', linestyle='-', color='g', label='Simple Rust')
plt.plot(x, y4, marker='d', linestyle='-', color='m', label='Line Rust')


plt.xlabel('Matrix Size')
plt.ylabel('Execution Time (s)')
plt.title('Execution time comparison between Simple and Line Multiplication in C++ and Rust', pad=15)
plt.xticks(x) 
plt.legend()
plt.grid(True)

plt.show()
