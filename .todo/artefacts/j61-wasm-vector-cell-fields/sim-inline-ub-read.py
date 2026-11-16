#!/usr/bin/env python3
# Simulation: _ub_read inline. usage: sim-inline-ub-read.py in.wat out.wat UBFUNC INTNEW SENTINEL_GLOBAL
import re, sys
L = open(sys.argv[1]).read().split('\n')
UB, NEW, G = '$#func' + sys.argv[3], '$#func' + sys.argv[4], '$#global' + sys.argv[5]
out = []; n = 0; i = 0
while i < len(L):
    s = L[i].strip(); ind = re.match(r'\s*', L[i]).group(0)
    if s == f'call {UB}' and out[-1].strip().startswith('local.get') and out[-2].strip().startswith('local.get'):
        r = out.pop().strip(); sh = out.pop().strip()
        out += [ind + x for x in [sh, f'global.get {G}', 'ref.eq', 'if (result eqref)', r, 'i64.const 33', 'i64.shl', 'i64.const 33', 'i64.shr_s', r, 'i64.eq',
                                   'if (result eqref)', r, 'i32.wrap_i64', 'ref.i31', 'else', r, f'call {NEW}', 'end', 'else', sh, 'end']]
        n += 1; i += 1; continue
    out.append(L[i]); i += 1
print('ub sites', n, file=sys.stderr)
open(sys.argv[2], 'w').write('\n'.join(out))
