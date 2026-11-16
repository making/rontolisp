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
        a = sorted(xs)
        t += a[r]
    return t

def runs(n, rounds):
    xs = numbers(n)
    words = ["w" + str(x) for x in xs]
    t = 0
    for r in range(rounds):
        a = sorted(words)
        t += len(a[r])
    return t

print(run(50000, ROUNDS), runs(50000, ROUNDS))
