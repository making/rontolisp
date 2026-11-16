#!/usr/bin/env python3
# Simulation on `wasm-tools print --name-unnamed` text: every cell gets a typed data
# field ($vec), set where a general array is made; the nine-test arm becomes two tests.
# usage: sim-cell-fields.py in.wat out.wat CELL CONS BUCKETS  (type names without the $#type prefix)
import re, sys
L = open(sys.argv[1]).read().split('\n')
CELL, CONS, BK = ['$#type' + x for x in sys.argv[3:6]]
out = []
i = 0
arms = news = 0
bk_line = [l for l in L if l.startswith(f'  (type {BK} ')][0]
need_locals = False
func_at = None
def flush_locals():
    global need_locals
    if need_locals and func_at is not None:
        out.insert(func_at + 1, f'    (local $vd (ref null {BK})) (local $vi i32)')
    need_locals = False
while i < len(L):
    l = L[i]; s = l.strip(); ind = re.match(r'\s*', l).group(0)
    if l.startswith('  (func '):
        flush_locals(); func_at = len(out)
    if l == bk_line:
        i += 1; continue
    if s.startswith(f'(type {CELL} '):
        out.append('    ' + bk_line.strip())
        out.append(l.replace('(field $#field0 (mut eqref))', f'(field $#field0 (mut eqref)) (field $vec (mut (ref null {BK})))'))
        i += 1; continue
    if l.startswith('  (global') and f'struct.new {CELL}' in l:
        l = l.replace(f'struct.new {CELL}', f'ref.null {BK} struct.new {CELL}')
    if s == f'struct.new {CELL}':
        g = L[i-3].strip()
        if g.startswith('local.get') and L[i-1].strip() == f'struct.new {CONS}' and L[i-2].strip() == f'struct.new {CONS}':
            out += [ind + g, ind + f'ref.test (ref {BK})', ind + f'if (result (ref null {BK}))', ind + '  ' + g, ind + f'  ref.cast (ref {BK})', ind + 'else', ind + f'  ref.null {BK}', ind + 'end']
            news += 1
        else:
            out.append(ind + f'ref.null {BK}')
        out.append(l); i += 1; continue
    first = re.fullmatch(rf'br_on_cast_fail (\S+) eqref \(ref {re.escape(CELL)}\)', s) or (s == f'ref.cast (ref {CELL})')
    if first and L[i+1].strip() == f'struct.get {CELL} $#field0' and L[i+2].strip().startswith('local.tee') and L[i+4].strip() == f'struct.get {CONS} $#field0' and L[i+7].strip() == 'array.len':
        e = i
        while not re.fullmatch(r'br \S+', L[e].strip()):
            e += 1
        body = [x.strip() for x in L[i:e+1]]
        q = [k for k in range(len(body)) if body[k] == 'i31.get_s'][0] - 1
        idx, lab = body[q-1], body[q]
        miss = [x for x in body if x.startswith('br_if')][0]
        new = [s, f'struct.get {CELL} $vec', 'local.tee $vd', 'ref.is_null', miss, idx, lab, 'i31.get_s', 'local.tee $vi', 'local.get $vd', 'array.len', 'i32.ge_u', miss, 'local.get $vd', 'local.get $vi']
        a = [k for k in range(len(body)) if body[k].startswith('array.set')]
        if a:
            new += [body[a[0]-1], body[a[0]], body[a[0]+1], body[-1]]
        else:
            new += [f'array.get {BK}', body[-1]]
        out += [ind + x for x in new]
        arms += 1; need_locals = True
        i = e + 1; continue
    out.append(l); i += 1
flush_locals()
print('arms', arms, 'array makes', news, file=sys.stderr)
open(sys.argv[2], 'w').write('\n'.join(out))
