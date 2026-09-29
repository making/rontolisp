"""refold.py FILE...: after pool2.py, a reference it left spelled out --
cp.entries().methodRefEntry(c, cp.entries().nameAndTypeEntry(cp.utf8Entry(n), cp.utf8Entry(d)))
(a call chain the formatter had split over lines when pool2.py first ran) -- becomes
cp.methodRef(c, n, d); fieldRef and interfaceMethodRef alike. Same minting order."""
import re
import sys

import pool2

KINDS = {"methodRefEntry": "methodRef", "fieldRefEntry": "fieldRef", "interfaceMethodRefEntry": "interfaceMethodRef"}


def refold(text):
    for entry, kind in KINDS.items():
        def fn(m, a, kind=kind):
            pool = m.group(1)
            if len(a) != 2:
                return None
            owner, nat = a
            flat = re.sub(r"\s+", "", nat)
            mm = re.fullmatch(re.escape(pool) + r"\.entries\(\)\.nameAndTypeEntry\((.*)\)", flat, re.S)
            if not mm:
                return None
            n, d = pool2.split_args(mm.group(1))
            ns, ds = pool2.utf8_string(n, pool), pool2.utf8_string(d, pool)
            if ns is None or ds is None:
                return "%s.%s(%s, %s, %s)" % (pool, kind, owner, n, d)
            # the literal names keep their spelling (flattening dropped their spaces)
            nargs = pool2.split_args(re.fullmatch(r"\s*\S+?\.entries\(\)\s*\.nameAndTypeEntry\((.*)\)\s*", nat,
                                                  re.S).group(1))
            return "%s.%s(%s, %s, %s)" % (pool, kind, owner, pool2.utf8_string(nargs[0], pool),
                                          pool2.utf8_string(nargs[1], pool))
        text = pool2.rewrite_calls(text, r"((?:this\.)?\w+(?:\.\w+)*)\s*\.entries\(\)\s*\.%s\(" % entry, fn)
    return text


for path in pool2.paths(sys.argv[1:]):
    text = open(path, encoding="utf-8").read()
    new = refold(text)
    if new != text:
        open(path, "w", encoding="utf-8").write(new)
        print(path.rsplit("/", 1)[-1])
