#!/bin/bash
# functions.sh File.java: the functions of the program's wasm whose bytes differ between
# the two builds of size.sh (-Drontolisp.wasm.debug-func-sizes), largest growth first;
# lambdas and top-level chunks, whose numbers move, are summed as one row.
BEFORE=$(realpath "${BEFORE:?the exec jar to compare against}")
AFTER=$(realpath "${AFTER:-target/classes}")
o=$(mktemp -d)
java -Drontolisp.wasm.debug-func-sizes=true -jar "$BEFORE" "$1" -o "$o/before.wasm" > "$o/before.txt" 2>&1
java -Drontolisp.wasm.debug-func-sizes=true -cp "$AFTER" am.ik.rontolisp.cli.RontoLispCli "$1" -o "$o/after.wasm" > "$o/after.txt" 2>&1
python3 - "$o" <<'PY'
import re, sys
def load(path):
    sizes = {}
    for line in open(path):
        m = re.match(r'\[func-size\] (\d+)\t(\d+)\t(.*)', line)
        if m:
            name = m.group(3).strip()
            if name.startswith(('_lambda_', '_toplevel_chunk', '_func_')):
                name = '(anonymous)'
            sizes[name] = sizes.get(name, 0) + int(m.group(1))
    return sizes
before, after = load(sys.argv[1] + '/before.txt'), load(sys.argv[1] + '/after.txt')
rows = [(after.get(k, 0) - before.get(k, 0), k, before.get(k, 0), after.get(k, 0)) for k in set(before) | set(after)]
for row in sorted(r for r in rows if r[0]):
    print(row)
print('functions', sum(r[0] for r in rows), 'of', sum(before.values()), '->', sum(after.values()))
PY
