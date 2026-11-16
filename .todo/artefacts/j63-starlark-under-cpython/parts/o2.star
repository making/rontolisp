def numbers(n):
    xs = []
    x = 12345
    for i in range(n):
        x = (x * 1103515245 + 12345) % 2147483648
        xs.append(x % 100000)
    return xs
def run(n, rounds):
    xs = numbers(n)
    t = 0
    for r in range(rounds):
        c = sorted(xs, key = lambda x: x % 1000)
        t += c[r]
    return t
print(run(50000, 24))
