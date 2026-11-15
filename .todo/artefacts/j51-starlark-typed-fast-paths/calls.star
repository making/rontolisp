def f(a, b = 1, c = 2):
    return a + b + c
def run(n):
    t = 0
    for i in range(n):
        t += f(i) + f(i, c = 3)
    return t
print(run(3000000))
