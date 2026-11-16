# Comprehensions: list and dict, with a condition, nested, over a list and over range.
def run(n, rounds):
    t = 0
    for r in range(rounds):
        xs = [i * 2 for i in range(n)]
        ys = [x + r for x in xs if x % 3 == 0]
        d = {x: x + 1 for x in ys}
        pairs = [(i, j) for i in range(30) for j in range(30) if i != j]
        zs = [p[0] * p[1] for p in pairs]
        t += len(xs) + len(ys) + len(d) + len(zs) + ys[-1] + zs[-1] + d[ys[0]]
    return t

print(run(100000, 80))
