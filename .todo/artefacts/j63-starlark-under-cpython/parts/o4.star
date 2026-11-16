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
        t += min(xs) + max(xs)
        for x in reversed(xs):
            t += x
            break
    return t
print(run(50000, 24))
