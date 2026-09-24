"""List the largest code entries of a core wasm module: size, function index (imports included), local count."""
import sys


def leb(b, p):
    r = s = 0
    while True:
        x = b[p]
        p += 1
        r |= (x & 0x7f) << s
        s += 7
        if not x & 0x80:
            return r, p


data = open(sys.argv[1], 'rb').read()
top = int(sys.argv[2]) if len(sys.argv) > 2 else 10
p = 8
nimp_funcs = 0
entries = []
while p < len(data):
    sid = data[p]
    p += 1
    size, p = leb(data, p)
    end = p + size
    if sid == 2:
        q = p
        n, q = leb(data, q)
        for _ in range(n):
            l, q = leb(data, q)
            q += l
            l, q = leb(data, q)
            q += l
            kind = data[q]
            q += 1
            if kind == 0:
                nimp_funcs += 1
                _, q = leb(data, q)
            elif kind == 1:
                q += 1
                flag = data[q]
                q += 1
                _, q = leb(data, q)
                if flag & 1:
                    _, q = leb(data, q)
            elif kind == 2:
                flag = data[q]
                q += 1
                _, q = leb(data, q)
                if flag & 1:
                    _, q = leb(data, q)
            elif kind == 3:
                t = data[q]
                q += 1
                if t in (0x63, 0x64):
                    _, q = leb(data, q)
                q += 1
            elif kind == 4:
                q += 1
                _, q = leb(data, q)
    if sid == 10:
        q = p
        n, q = leb(data, q)
        for i in range(n):
            sz, q = leb(data, q)
            body_start = q
            groups, r = leb(data, q)
            nl = 0
            for _ in range(groups):
                c, r = leb(data, r)
                nl += c
                t = data[r]
                r += 1
                if t in (0x63, 0x64):
                    _, r = leb(data, r)
            entries.append((sz, nimp_funcs + i, nl))
            q = body_start + sz
    p = end
entries.sort(reverse=True)
total = sum(e[0] for e in entries)
print("module", len(data), "code entries", len(entries), "code bytes", total)
for sz, idx, nl in entries[:top]:
    print(sz, "func", idx, "locals", nl)
