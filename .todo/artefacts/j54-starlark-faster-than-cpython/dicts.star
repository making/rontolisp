# Dict get, set, membership and iteration, with int and string keys.
def count_ints(n, m):
    d = {}
    for i in range(n):
        k = (i * 31) % m
        d[k] = d.get(k, 0) + 1
    return d

def count_words(words, n):
    d = {}
    w = len(words)
    for i in range(n):
        k = words[(i * 7) % w]
        if k in d:
            d[k] += 1
        else:
            d[k] = 1
    return d

def total(d):
    t = 0
    for k in d:
        t += d[k]
    for k, v in d.items():
        t += v
    for v in d.values():
        t += v
    return t + len(d.keys())

def run(n):
    words = ["alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta",
             "iota", "kappa", "lambda", "mu", "nu", "xi", "omicron", "pi", "rho"]
    a = count_ints(n, 5003)
    b = count_words(words, n)
    t = total(a) + total(b)
    for i in range(n):
        if i in a:
            t += 1
    return t + b["gamma"]

print(run(2400000))
