def run(n):
    t = 0
    for i in range(n):
        xs = [1, 2, 3, 4, 5, 6, 7, 8]
        t += len(xs)
        if "c" in ("a", "b", "c"):
            t += 1
    return t
print(run(3000000))
