"""Largest function bodies (bytes) of a core module. usage: bodysize.py file.wasm [top]"""
import sys

b = open(sys.argv[1], "rb").read()
top = int(sys.argv[2]) if len(sys.argv) > 2 else 5


def leb(i):
    r = s = 0
    while True:
        x = b[i]
        i += 1
        r |= (x & 0x7F) << s
        s += 7
        if x < 0x80:
            return r, i


i = 8
imports = 0
while i < len(b):
    sid = b[i]
    size, j = leb(i + 1)
    if sid == 2:
        # count function imports
        n, k = leb(j)
        for _ in range(n):
            ml, k = leb(k)
            k += ml
            fl, k = leb(k)
            k += fl
            kind = b[k]
            k += 1
            if kind == 0:
                imports += 1
                _, k = leb(k)
            elif kind == 1:
                k += 1
                flags, k = leb(k + 0)
                _, k = leb(k)
                if flags & 1:
                    _, k = leb(k)
            elif kind == 2:
                flags, k = leb(k)
                _, k = leb(k)
                if flags & 1:
                    _, k = leb(k)
            elif kind == 3:
                k += 1  # valtype (simple)
                k += 1
            elif kind == 4:
                k += 1
                _, k = leb(k)
    if sid == 10:
        n, k = leb(j)
        sizes = []
        for f in range(n):
            sz, k2 = leb(k)
            sizes.append((sz, imports + f))
            k = k2 + sz
        total = sum(s for s, _ in sizes)
        sizes.sort(reverse=True)
        print("code bytes", total)
        for sz, idx in sizes[:top]:
            print("func", idx, sz, "%.1f%%" % (100.0 * sz / len(b)))
    i = j + size
