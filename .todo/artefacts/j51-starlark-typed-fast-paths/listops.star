def run(n):
    xs = []
    for i in range(n):
        xs.append(i)
    t = 0
    for i in range(n):
        t += xs.pop()
    for i in range(n // 10):
        xs.insert(0, i)
    for i in range(n // 10):
        xs.remove(i)
    return t + len(xs)
print(run(30000))
