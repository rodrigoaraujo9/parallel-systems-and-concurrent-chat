import matplotlib.pyplot as plt

x = [600, 1000, 1400, 1800, 2200, 2600, 3000]

y1 = [244746909, 1224855166, 3504346526, 9088237338, 17633163340, 30895738335, 50302223677]
y2 = [39650826, 316932084, 1471699812, 8050019145, 17633163340, 51226290070, 95427883578]
y3 = [163385, 7423584, 181429536, 872507420, 1781935594, 3098624125, 5010847179]

y4 = [27109199, 125765602, 346263842, 746043099, 2075596726, 4413574600, 6781583602]
y5 = [57927022, 264527983, 710633295, 1452571860, 2574411737, 4176765811, 6346146672]
y6 = [196242, 6473679, 157113420, 566433941, 1198107104, 2140415980, 3441710789]

plt.figure(figsize=(8, 5))

plt.plot(x, y1, marker='o', linestyle='-', color='b', label='L1 Cache Misses (Simple)')
plt.plot(x, y4, marker='v', linestyle='-', color='c', label='L1 Cache Misses (Line)')

plt.plot(x, y2, marker='s', linestyle='-', color='r', label='L2 Cache Misses (Simple)')
plt.plot(x, y5, marker='D', linestyle='-', color='m', label='L2 Cache Misses (Line)')

plt.plot(x, y3, marker='^', linestyle='-', color='g', label='L3 Cache Misses (Simple)')
plt.plot(x, y6, marker='P', linestyle='-', color='orange', label='L3 Cache Misses (Line)')



plt.xlabel('Matrix Size')
plt.ylabel('Cache Misses')
plt.title('L1, L2 and L3 cache misses between Simple and Line Multiplication Matrices in C/C++', pad=20)
plt.xticks(x) 
plt.legend()
plt.grid(True)

plt.show()
