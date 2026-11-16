# sorted of ints, of strings, by a key, reversed; min, max, reversed.
def numbers(n):
    xs = []
    x = 12345
    for i in range(n):
        x = (x * 1103515245 + 12345) % 2147483648
        xs.append(x % 100000)
    return xs

def run(n, rounds):
    xs = numbers(n)
    words = ["w" + str(x) for x in xs]
    t = 0
    for r in range(rounds):
        a = sorted(xs)
        b = sorted(xs, reverse = True)
        c = sorted(xs, key = lambda x: x % 1000)
        w = sorted(words)
        t += a[r] + b[r] + c[r] + len(w[r]) + min(xs) + max(xs)
        for x in reversed(a):
            t += x
            break
    return t

print(run(50000, 24))
