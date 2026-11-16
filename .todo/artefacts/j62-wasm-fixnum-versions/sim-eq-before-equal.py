#!/usr/bin/env python3
# sim-eq-before-equal.py in.wat out.wat EQUAL : `local.get a; local.get b; call _equal` asks
# ref.eq first (EQUAL is _equal's function number in the module's text)
import re, sys
s = open(sys.argv[1]).read()
pat = re.compile(r'( +)local\.get (\$#local\d+)\n +local\.get (\$#local\d+)\n +call \$#func%s\n' % sys.argv[3])
n = 0
def rep(m):
    global n
    n += 1
    i, a, b = m.group(1), m.group(2), m.group(3)
    return (f"{i}local.get {a}\n{i}local.get {b}\n{i}ref.eq\n{i}if (result i32)\n{i}i32.const 1\n{i}else\n"
            f"{i}local.get {a}\n{i}local.get {b}\n{i}call $#func{sys.argv[3]}\n{i}end\n")
open(sys.argv[2], 'w').write(pat.sub(rep, s))
print('sites', n)
