def numbers(n, seed, span, base):
    xs = []
    x = seed
    for i in range(n):
        x = (x * 1103515245 + 12345) % 2147483648
        xs.append(x % span + base)
    return xs

def check(xs):
    a = sorted(xs)
    for i in range(1, len(a)):
        if a[i - 1] > a[i]:
            return "unsorted"
    b = sorted(xs, reverse = True)
    for i in range(1, len(b)):
        if b[i - 1] < b[i]:
            return "unsorted descending"
    t = 0
    for i in range(len(a)):
        t = (t * 31 + a[i] + 7 * b[i]) % 1000000007
    return t

def stable(n, span, reverse):
    xs = numbers(n, 99, 1000000, -500000)
    pairs = [(x, i) for i, x in enumerate(xs)]
    s = sorted(pairs, key = lambda p: p[0] % span - span // 2, reverse = reverse)
    t = 0
    for i in range(1, len(s)):
        ka = s[i - 1][0] % span
        kb = s[i][0] % span
        if ka == kb and s[i - 1][1] > s[i][1]:
            return "unstable"
        if (ka < kb) == reverse and ka != kb:
            return "unsorted"
    for p in s:
        t = (t * 31 + p[1]) % 1000000007
    return t

print(check(numbers(10, 1, 100, 0)), check(numbers(63, 2, 100, -50)), check(numbers(64, 3, 100, -50)))
print(check(numbers(300, 4, 7, 0)), check(numbers(2047, 5, 1000000, -400000)), check(numbers(2048, 6, 1000000, -400000)))
print(check(numbers(5000, 7, 2147483648, 0)), check(numbers(5000, 8, 2147483648, -1073741824)))
print(check(numbers(100, 9, 1, 5)), check([4294967295 + x for x in numbers(100, 10, 50, 0)] + [0]))
print(check([4294967296 + x for x in numbers(100, 11, 50, 0)] + [0]), check([(1 << 70) + x for x in numbers(100, 12, 50, 0)] + [-(1 << 70)]))
print(stable(50, 7, False), stable(500, 7, True), stable(3000, 13, False), stable(3000, 13, True), stable(3000, 100000, True))
print(sorted([3.5, 1, 2] * 30)[:5], sorted([1.0, 1, 0.5] * 30)[28:33], sorted(["b", "a", "c"] * 30)[29:31])
print(sorted([True, False] * 40)[38:42], sorted([(2, "x"), (1, "y")] * 40)[39:41])
print(sorted(numbers(100, 13, 10, 0), key = lambda x: str(x))[:12], sorted(numbers(100, 14, 10, 0), key = lambda x: -x)[:12])
print(sorted(numbers(70, 15, 5, 0), key = lambda x: 1.5 * x)[10:20], sorted(range(100), key = lambda x: x % 3, reverse = True)[30:38])
print(sorted({i: 1 for i in range(100, 0, -1)})[:5], sorted((5, 3, 1)), sorted("cba".elems()), sorted([]), sorted([7]))
big = [(1 << 70) + (i * 7919) % 1000 for i in range(200)]
big_a = sorted(big)
big_b = sorted(big, reverse = True)
big_c = sorted(big, key = lambda v: -(v % 10) * (1 << 65))
print(big_a[0], big_a[199], big_b[0] == big_a[199], big_a == sorted(big_b), big_c[0] % 10, big_c[199] % 10, [v % 10 for v in big_c[:12]])
wide = [-(1 << 62) + i % 3 for i in range(100)] + [(1 << 62) - 1]
print(sorted(wide)[0], sorted(wide)[100], sorted([x - 5 for x in range(100)], key = lambda v: v // 10)[:12])
print(sorted([4294967295 - i for i in range(70)] + [0])[:2], sorted([4294967296 - i for i in range(70)] + [0])[:2])
