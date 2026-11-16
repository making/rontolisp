def run(n):
    xs = list(range(n))
    t = 0
    for i in range(n // 2):
        t += xs.pop()
    return t
print(run(2400000))
