#!/usr/bin/env python3
# funcsizes.py MODULE.wasm DUMP [TARGET...]
#
# DUMP is the stderr of `java -Drontolisp.wasm.debug-func-sizes=1 -jar ...`. Prints the
# bytes by kind, the arms of each funcall dispatcher, and for each TARGET the shortest
# call path from an export.
import collections, re, subprocess, sys

wasm, dump, *targets = sys.argv[1:]
names, sizes = {}, {}
for line in open(dump):
    m = re.match(r'\[func-size\]\s+(\d+)\s+(\d+)\s+(.+)', line.strip())
    if m:
        sizes[int(m[2])], names[int(m[2])] = int(m[1]), m[3]
index = {name: i for i, name in names.items()}

def kind(name):
    for pattern, label in ((r'^%FMT', 'format interpreter'), (r'^%(POS|PC)-', 'printer walks'),
                           (r'^FUNC_', 'runtime helpers'), (r'^_dispatch', 'dispatchers'),
                           (r'^_(lambda|func)', 'lambdas and generated'), (r'^[A-Z-]+::?', 'program')):
        if re.match(pattern, name):
            return label
    return 'prelude'

totals, counts = collections.Counter(), collections.Counter()
for i, size in sizes.items():
    totals[kind(names[i])] += size
    counts[kind(names[i])] += 1
for label, size in totals.most_common():
    print(f'{size:7d} {counts[label]:4d} {label}')

text = subprocess.run(['wasm-tools', 'print', wasm], capture_output=True, text=True).stdout
current, calls, exports = None, collections.defaultdict(set), {}
for line in text.splitlines():
    m = re.match(r'\s*\(func \(;(\d+);\)', line)
    if m:
        current = int(m[1])
    m = re.match(r'\s*\(export "([^"]+)" \(func (\d+)\)', line)
    if m:
        exports[int(m[2])] = m[1]
    if current is not None:
        for direct, ref in re.findall(r'\b(?:return_)?call (\d+)|ref\.func (\d+)', line):
            calls[current].add(int(direct or ref))
for name in sorted(n for n in index if n.startswith('_dispatch')):
    print(name, sorted({names.get(c, str(c)) for c in calls[index[name]]}))

previous, queue = {e: None for e in exports}, collections.deque(exports)
while queue:
    u = queue.popleft()
    for v in sorted(calls[u]):
        if v not in previous:
            previous[v] = u
            queue.append(v)
for target in targets:
    u, path = index.get(target), []
    while u is not None:
        path.append(names.get(u, f'#{u}') + (f'[{exports[u]}]' if u in exports else ''))
        u = previous.get(u)
    print(target, ':', ' <- '.join(path) or 'not in the module')
