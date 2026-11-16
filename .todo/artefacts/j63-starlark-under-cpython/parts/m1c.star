def run(n):
    xs = list(range(n))
    t = 0
    for r in range(10):
        ys = list(xs)
        ys.extend(xs)
        t += len(ys) + ys.index(77)
    return t
print(run(1200000))
