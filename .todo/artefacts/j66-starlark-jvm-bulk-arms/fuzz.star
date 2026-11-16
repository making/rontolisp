def rng(seed):
    return [seed]

def nxt(r, n):
    r[0] = (r[0] * 1103515245 + 12345) % 2147483648
    return (r[0] // 65536) % n

ALPHABETS = [
    ["a", "b", "c"],
    ["a", "b", "é", "ÿ", "\u0001", " ", "!", "\""],
    ["a", "ÿ", "Ā", "～", "\U0001F600", "\U0001F601", "z"],
    ["あ", "い", "う", "a"],
    ["x"],
    ["\U0001F600", "￿", "", "퟿", "\U00010000"],
]

def word(r, alphabet, maxlen):
    n = nxt(r, maxlen + 1)
    return "".join([alphabet[nxt(r, len(alphabet))] for _ in range(n)])

def digest(xs):
    t = 0
    for x in xs:
        for c in x.codepoint_ords():
            t = (t * 131 + c) % 1000000007
        t = (t * 131 + 7) % 1000000007
    return t

def check_sorted(xs, reverse):
    for i in range(1, len(xs)):
        if reverse:
            if xs[i - 1] < xs[i]:
                return False
        elif xs[i - 1] > xs[i]:
            return False
    return True

def strings(seed, n, a, maxlen):
    r = rng(seed)
    ws = [word(r, ALPHABETS[a], maxlen) for _ in range(n)]
    up = sorted(ws)
    down = sorted(ws, reverse = True)
    pairs = [(w, i) for i, w in enumerate(ws)]
    by = sorted(pairs, key = lambda p: p[0])
    byd = sorted(pairs, key = lambda p: p[0], reverse = True)
    stable = True
    for i in range(1, len(by)):
        if by[i - 1][0] == by[i][0] and by[i - 1][1] > by[i][1]:
            stable = False
        if byd[i - 1][0] == byd[i][0] and byd[i - 1][1] > byd[i][1]:
            stable = False
    sep = ALPHABETS[a][nxt(r, len(ALPHABETS[a]))] + ALPHABETS[a][nxt(r, len(ALPHABETS[a]))]
    line = sep.join(ws)
    one = ALPHABETS[a][0]
    parts = line.split(one)
    lim = line.split(one, 3)
    hay = line[:200]
    return [digest(up), digest(down), check_sorted(up, False), check_sorted(down, True), stable, [p[1] for p in by[:5]], [p[1] for p in byd[:5]],
            len(line), digest(parts), len(parts), digest(lim), one.join(parts) == line, line.count(one), line.count(sep), line.count(sep, 5, 150), line.count(one + one),
            hay.find(sep), hay.find(sep, 7), hay.find(one, 3, 90), hay.rfind(one), sep in hay, hay.replace(one, "-")[:20], hay.find(""), hay.find("", 201), hay.find(one, 200)]

def ints(seed, n, span, base):
    r = rng(seed)
    xs = [nxt(r, span) * (nxt(r, 3) - 1) + base + nxt(r, span) for _ in range(n)]
    up = sorted(xs)
    down = sorted(xs, reverse = True)
    t = 0
    for i in range(n):
        t = (t * 31 + up[i] + 7 * down[i]) % 1000000007
    pairs = [(x, i) for i, x in enumerate(xs)]
    by = sorted(pairs, key = lambda p: p[0] % 7 - 3, reverse = (seed % 2 == 0))
    tup = sorted(tuple(xs), key = lambda v: -v)
    return [t, [p[1] for p in by[:6]], tup[:3], tup == down, sorted(tuple(xs))[:2], list(tuple(xs)) == xs, tuple(xs)[1:4] == tuple(xs[1:4])]

def main():
    for a in range(len(ALPHABETS)):
        for n in [0, 1, 2, 5, 31, 32, 33, 34, 100, 700]:
            for maxlen in [1, 3, 7, 8, 9, 14]:
                print(a, n, maxlen, strings(a * 1000 + n * 10 + maxlen, n, a, maxlen))
    for n in [0, 1, 2, 31, 32, 33, 34, 300, 5000]:
        for span, base in [(1, 5), (3, -1), (256, -128), (70000, 0), (2147483647, -1073741824), (2147483647, (1 << 61)), (2147483647, -(1 << 62))]:
            print(n, span, ints(n * 7 + span, n, span, base))
    print(sorted((3, False, 1, True), key = lambda v: 0 if type(v) == "bool" else v), list((1, False, 2)), sorted([(1 << 63) - 1, -(1 << 63), 0, 1 << 63][:3]), sorted([(1 << 63) - 1, -(1 << 63), 0, 1 << 63]))

main()
