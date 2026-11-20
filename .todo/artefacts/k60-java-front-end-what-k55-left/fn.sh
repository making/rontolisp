#!/bin/bash
# fn.sh A.jar B.jar File.java: functions whose wasm bytes differ between two exec jars
A=$1; B=$2; f=$3
o=$(mktemp -d)
java -Drontolisp.wasm.debug-func-sizes=true -jar "$A" "$f" -o "$o/a.wasm" > "$o/a.txt" 2>&1
java -Drontolisp.wasm.debug-func-sizes=true -jar "$B" "$f" -o "$o/b.wasm" > "$o/b.txt" 2>&1
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
before, after = load(sys.argv[1] + '/a.txt'), load(sys.argv[1] + '/b.txt')
rows = [(after.get(k, 0) - before.get(k, 0), k, before.get(k, 0), after.get(k, 0)) for k in set(before) | set(after)]
for row in sorted(r for r in rows if r[0]):
    print(row)
print('functions', sum(r[0] for r in rows), 'of', sum(before.values()), '->', sum(after.values()))
PY
