print("a,b,,c".split(","))
print("a,b,,c".split(",", 1))
print("abc".split(","))
print("".split(","))
print("a--b--c--".split("--"))
print("aaa".split("aa"))
print("héllo wörld ünï".split(" "))
print(len("héllo wörld ünï".split(" ")[0]))
print(",".join(["a", "bb", "ccc"]))
print("".join([]))
print("-".join(["x"]))
print(", ".join(["é", "ö" * 20, "z"]), len(", ".join(["é", "ö" * 20, "z"])))
print(sorted([3, 1, 2, -5, 10000000000, -10000000000, 7, 7, 0]))
print(sorted([3, 1, 2, -5, 10000000000, 7], reverse = True))
print(sorted(["b", "a", "abc", "ab", "", "abcdefghij", "abcdefghi", "abcdefgh", "abcdefg", "abcdefghik", "é", "z"]))
print(sorted(["b", "a", "abc", "ab", "", "abcdefghij", "abcdefghi", "abcdefgh", "abcdefg", "abcdefghik", "é", "z"], reverse = True))
print(sorted([(2, "b"), (1, "a"), (2, "a"), (1, "b")], key = lambda p: p[0]))
print(sorted([(2, "b"), (1, "a"), (2, "a"), (1, "b")], key = lambda p: p[0], reverse = True))
print(sorted(["bb", "a", "ccc", "dd"], key = len))
print(sorted([1.5, 1, 0.5]))
xs = sorted(["q", "p"])
xs.append("r")
print(xs, len(xs))
ys = "a b c".split(" ")
ys.append("d")
ys.extend(["e", "f", "g", "h", "i", "j"])
print(ys, len(ys), ys.pop(), ys[0])
def lcg(n, m):
    xs = []
    x = 12345
    for i in range(n):
        x = (x * 1103515245 + 12345) % 2147483648
        xs.append(x % m)
    return xs
def check(xs, ys, desc):
    for i in range(len(ys) - 1):
        if desc:
            if ys[i] < ys[i + 1]:
                return "BAD"
        elif ys[i] > ys[i + 1]:
            return "BAD"
    return len(xs) == len(ys)
for m in [3, 300, 70000, 2147483648]:
    xs = lcg(3000, m)
    print(m, check(xs, sorted(xs), False), check(xs, sorted(xs, reverse = True), True), sorted(xs)[:3], sorted(xs, reverse = True)[:3])
    big = [x * 4294967296 - 1000000000000 for x in xs]
    print(check(big, sorted(big), False), check(big, sorted(big, reverse = True), True))
    ws = ["k" + str(x) for x in xs]
    print(check(ws, sorted(ws), False), check(ws, sorted(ws, reverse = True), True), sorted(ws)[:3])
    ls = ["commonprefix-" + str(x) + "-tail" for x in xs]
    print(check(ls, sorted(ls), False), check(ls, sorted(ls, reverse = True), True), sorted(ls)[:2], sorted(ls, reverse = True)[:2])
    # stability: pairs by a key with many ties
    ps = [(x % 7, i) for i, x in enumerate(xs)]
    a = sorted(ps, key = lambda p: p[0])
    b = sorted(ps, key = lambda p: p[0], reverse = True)
    c = sorted(ps, key = lambda p: "commonprefix" + str(p[0]))
    d = sorted(ps, key = lambda p: "commonprefix" + str(p[0]), reverse = True)
    ok = True
    for q in [a, b, c, d]:
        for i in range(len(q) - 1):
            if q[i][0] == q[i + 1][0] and q[i][1] > q[i + 1][1]:
                ok = False
    print(ok, a[0], b[0], c[0], d[0], a == c, b == d)
