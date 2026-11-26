"""Per-function GC allocation census of wasm modules: the largest count of struct.new / array.new*
in ONE function body. usage: allocs.py file.wasm...  (a component is split with wasm-tools first)"""
import re
import subprocess
import sys

ALLOC = re.compile(r"^\s*(struct\.new|struct\.new_default|array\.new|array\.new_default|array\.new_fixed|array\.new_data|array\.new_elem)\b")
FUNC = re.compile(r"^\s*\(func (\$\S+|\(;\d+;\))")


def census(path):
    text = subprocess.run(["wasm-tools", "print", path], capture_output=True, text=True).stdout
    best = []
    name = None
    count = 0
    total = 0
    funcs = 0
    for line in text.splitlines():
        m = FUNC.match(line)
        if m:
            if name is not None:
                best.append((count, name))
            name = m.group(1)
            count = 0
            funcs += 1
            continue
        if ALLOC.match(line):
            count += 1
            total += 1
    if name is not None:
        best.append((count, name))
    best.sort(reverse=True)
    return funcs, total, best[:3]


for p in sys.argv[1:]:
    funcs, total, top = census(p)
    print("%s\tfuncs=%d\tallocs=%d\ttop=%s" % (p.rsplit("/", 1)[-1], funcs, total, " ".join("%d:%s" % t for t in top)))
