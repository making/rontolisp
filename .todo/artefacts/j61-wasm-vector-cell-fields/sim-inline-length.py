#!/usr/bin/env python3
# On the output of sim-cell-fields.py: `length` (call FUNC) inline for a cell with $vec: the fill pointer off
# the header, else the vector's own length. usage: sim-inline-length.py in out CELL CONS BK FUNC mode [funcs...]
# mode: walk = read the fill pointer through the header; len = assume no header walk (upper bound: array.len)
import re, sys
L = open(sys.argv[1]).read().split('\n')
CELL, CONS, BK = ['$#type' + x for x in sys.argv[3:6]]
FUNC = '$#func' + sys.argv[6]
mode = sys.argv[7]
only = set('$#func' + x for x in sys.argv[8:])
out = []; cur = None; func_at = None; need = False; n = 0
def flush():
    global need
    if need:
        out.insert(func_at + 1, f'    (local $lx eqref) (local $lc (ref null {CELL})) (local $ld (ref null {BK})) (local $lf eqref)')
    need = False
for l in L:
    s = l.strip(); ind = re.match(r'\s*', l).group(0)
    if l.startswith('  (func '):
        flush(); func_at = len(out); cur = l.split()[1]
    if s == f'call {FUNC}' and (not only or cur in only):
        need = True; n += 1
        new = ['block $ldone (param eqref) (result eqref)', ' block $lslow (param eqref) (result eqref)', '  local.tee $lx',
               f'  br_on_cast_fail $lslow eqref (ref {CELL})', '  local.tee $lc', f'  struct.get {CELL} $vec', '  local.tee $ld', '  ref.is_null',
               '  if', '   local.get $lx', '   br $lslow', '  end']
        if mode == 'walk':
            new += ['  local.get $lc', f'  struct.get {CELL} $#field0', f'  ref.cast (ref {CONS})', f'  struct.get {CONS} $#field1',
                    f'  ref.cast (ref {CONS})', f'  struct.get {CONS} $#field0', f'  ref.cast (ref {CONS})', f'  struct.get {CONS} $#field0',
                    '  local.tee $lf', '  ref.test (ref i31)', '  if (result eqref)', '   local.get $lf', '  else', '   local.get $ld', '   array.len', '   ref.i31', '  end']
        else:
            new += ['  local.get $ld', '  array.len', '  ref.i31']
        new += ['  br $ldone', ' end', f' call {FUNC}', 'end']
        out += [ind + x for x in new]
        continue
    out.append(l)
flush()
print('length sites', n, file=sys.stderr)
open(sys.argv[2], 'w').write('\n'.join(out))
