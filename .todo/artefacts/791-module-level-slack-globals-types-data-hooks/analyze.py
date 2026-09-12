#!/usr/bin/env python3
"""Per-function call-site census over a core wasm module.

usage: analyze.py module.wasm [sizes.txt] [survivors.wat]
  sizes.txt   : the -Drontolisp.wasm.debug-func-sizes dump (names by final index)
  survivors.wat: binaryen -S output whose $N names are the ORIGINAL indices
"""
import re, subprocess, sys, collections

def leb_u(b, p):
    r = 0; s = 0
    while True:
        x = b[p]; p += 1
        r |= (x & 0x7f) << s; s += 7
        if not (x & 0x80): return r, p

def sections(b):
    p = 8; out = []
    while p < len(b):
        sid = b[p]; p += 1
        n, p = leb_u(b, p)
        out.append((sid, b[p:p+n])); p += n
    return out

def import_func_count(payload):
    p = 0; n, p = leb_u(payload, p); c = 0
    for _ in range(n):
        l, p = leb_u(payload, p); p += l
        l, p = leb_u(payload, p); p += l
        kind = payload[p]; p += 1
        if kind == 0:
            _, p = leb_u(payload, p); c += 1
        elif kind == 1:
            p += 1  # reftype (single byte for abstract)
            flags = payload[p]; p += 1
            _, p = leb_u(payload, p)
            if flags & 1: _, p = leb_u(payload, p)
        elif kind == 2:
            flags = payload[p]; p += 1
            _, p = leb_u(payload, p)
            if flags & 1: _, p = leb_u(payload, p)
        elif kind == 3:
            p += 2
        elif kind == 4:
            p += 1; _, p = leb_u(payload, p)
    return c

def code_sizes(payload):
    p = 0; n, p = leb_u(payload, p); out = []
    for _ in range(n):
        l, p = leb_u(payload, p); out.append(l); p += l
    return out

def main():
    wasm = sys.argv[1]
    b = open(wasm, 'rb').read()
    secs = dict()
    for sid, pl in sections(b):
        secs.setdefault(sid, pl)
    nimp = import_func_count(secs[2]) if 2 in secs else 0
    sizes = code_sizes(secs[10]) if 10 in secs else []
    wat = subprocess.run(['wasm-tools', 'print', wasm], capture_output=True, text=True, check=True).stdout
    names = {}
    if len(sys.argv) > 2 and sys.argv[2] != '-':
        for line in open(sys.argv[2]):
            m = re.match(r'\[func-size\] (\d+)\t(\d+)\t(.*)', line)
            if m: names[int(m.group(2))] = m.group(3)
    survivors = None
    if len(sys.argv) > 3:
        survivors = set(int(x) for x in re.findall(r'\(func \$(\d+)', open(sys.argv[3]).read()))
    cur = None; calls = collections.Counter(); callers = collections.defaultdict(set)
    lines = collections.Counter(); exported = set(); start = None
    for line in wat.splitlines():
        m = re.match(r'\s*\(func \(;(\d+);\)', line)
        if m: cur = int(m.group(1)); continue
        m = re.match(r'\s*\(export "[^"]*" \(func (\d+)\)', line)
        if m: exported.add(int(m.group(1))); continue
        m = re.match(r'\s*\(start (\d+)\)', line)
        if m: start = int(m.group(1)); continue
        if cur is None: continue
        lines[cur] += 1
        for m in re.finditer(r'\bcall (\d+)\b', line):
            t = int(m.group(1)); calls[t] += 1; callers[t].add(cur)
    total = nimp + len(sizes)
    rows = []
    for f in range(nimp, total):
        rows.append((f, sizes[f-nimp], calls[f], len(callers[f]), f in exported or f == start, names.get(f, '?'),
                     None if survivors is None else (f in survivors)))
    print(f"imports={nimp} defined={len(sizes)} code_bytes={sum(sizes)} exported={len(exported)} start={start}")
    hdr = "idx\tbytes\tcalls\tcallers\troot\tsurvived\tname"
    print(hdr)
    for r in sorted(rows, key=lambda r: -r[1]):
        print(f"{r[0]}\t{r[1]}\t{r[2]}\t{r[3]}\t{'R' if r[4] else ''}\t{'' if r[6] is None else ('keep' if r[6] else 'GONE')}\t{r[5]}")
    one = [r for r in rows if r[2] == 1 and not r[4]]
    zero = [r for r in rows if r[2] == 0 and not r[4]]
    print(f"\nsingle-call-site non-root: {len(one)} functions, {sum(r[1] for r in one)} bytes")
    print(f"zero-call non-root (dead?): {len(zero)} functions, {sum(r[1] for r in zero)} bytes")
    if survivors is not None:
        gone = [r for r in rows if r[6] is False]
        print(f"inlined away by binaryen: {len(gone)} functions, {sum(r[1] for r in gone)} bytes; "
              f"of which single-call-site: {sum(1 for r in gone if r[2]==1)} ({sum(r[1] for r in gone if r[2]==1)} B), "
              f"multi-call: {sum(1 for r in gone if r[2]>1)} ({sum(r[1] for r in gone if r[2]>1)} B)")
        by_calls = collections.Counter()
        for r in gone: by_calls[min(r[2], 10)] += 1
        print("  gone by call count:", sorted(by_calls.items()))
        big = [r for r in gone if r[2] > 1]
        print("  multi-call gone (bytes, calls, name):", [(r[1], r[2], r[5]) for r in sorted(big, key=lambda r:-r[1])[:40]])
        kept1 = [r for r in rows if r[6] and r[2] == 1 and not r[4]]
        print(f"  single-call-site KEPT by binaryen: {len(kept1)} ({sum(r[1] for r in kept1)} B):", [(r[1], r[5]) for r in sorted(kept1, key=lambda r:-r[1])[:40]])
if __name__ == "__main__":
    main()
