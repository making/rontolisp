def twice(x):
    return x * 2
def apply(f, n):
    t = 0
    for i in range(n):
        t += f(i)
    return t
print(apply(twice, 3000000))
