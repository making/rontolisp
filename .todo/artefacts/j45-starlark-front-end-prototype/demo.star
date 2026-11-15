# Starlark demo: closures, kwargs, comprehensions, dicts, strings, bigints
def fib(n):
    a, b = 0, 1
    for _ in range(n):
        a, b = b, a + b
    return a

def make_counter(start = 0, step = 1):
    state = [start]
    def bump(times = 1):
        for _ in range(times):
            state[0] += step
        return state[0]
    return bump

def describe(name, *args, sep = ", ", **kwargs):
    parts = [str(a) for a in args]
    for k in sorted(kwargs):
        parts.append("%s=%r" % (k, kwargs[k]))
    return "{}({})".format(name, sep.join(parts))

def primes(limit):
    out = []
    for n in range(2, limit):
        is_prime = True
        for p in out:
            if p * p > n:
                break
            if n % p == 0:
                is_prime = False
                break
        if not is_prime:
            continue
        out.append(n)
    return out

print("fib", [fib(i) for i in range(10)], fib(100))
c = make_counter(step = 5)
print(c(), c(times = 3), c(2))
print(describe("f", 1, 2.5, None, x = "a", y = [1, (2,)]))
print(describe("g", sep = "; ", *[True, False], **{"k": {"a": 1}}))
print(primes(50))
words = "the quick brown fox jumps over the lazy dog".split()
freq = {}
for w in words:
    freq[w] = freq.get(w, 0) + 1
print(freq, len(freq), "the" in freq, "cat" not in freq)
print(sorted(words, key = len, reverse = True)[:3], max(words), min(words, key = len))
print({k: v for k, v in zip(words, range(100)) if v % 2 == 0})
print([(x, y) for x in range(3) for y in "ab".elems() if x != 1])
print(1 // 2, -7 // 2, -7 % 3, 7 / 2, 2.0 * 3, 1 << 70, "ab" * 3, [0] * 3, (1, 2) + (3,))
print("a,b,c".split(",")[-1].upper(), "x".join(["1", "2"]), "hello"[1:4], "hello"[::-1])
print(None or "dflt", 0 and 1, [] or {}, not [], 3 if "" else 4)
print(type(1), type("s"), type([]), type({}), type(()), type(None), type(fib), type(len))
