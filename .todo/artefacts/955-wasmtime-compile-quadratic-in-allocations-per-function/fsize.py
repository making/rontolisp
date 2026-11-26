"""Largest functions of a module by instruction count, with their op histogram.
usage: fsize.py file.wasm [top]"""
import collections
import re
import subprocess
import sys

FUNC = re.compile(r"^  \(func (\$\S+|\(;\d+;\))")
text = subprocess.run(["wasm-tools", "print", sys.argv[1]], capture_output=True, text=True).stdout
top = int(sys.argv[2]) if len(sys.argv) > 2 else 5
funcs = []
cur = None
for line in text.splitlines():
    m = FUNC.match(line)
    if m:
        cur = [m.group(1), 0, collections.Counter(), 0]
        funcs.append(cur)
        continue
    if line.startswith("  (") and not line.startswith("  (func"):
        cur = None
        continue
    if cur is not None:
        s = line.strip()
        if not s or s.startswith("(local") or s.startswith("(param") or s.startswith("(result"):
            continue
        op = s.split()[0]
        cur[1] += 1
        cur[2][op] += 1
        cur[3] = max(cur[3], (len(line) - len(line.lstrip())) // 2)
funcs.sort(key=lambda f: -f[1])
for name, n, hist, depth in funcs[:top]:
    print(name, "instrs=%d maxnest=%d" % (n, depth), hist.most_common(14))
