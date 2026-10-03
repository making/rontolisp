# Decimal strings (shortest reprs of random bit patterns, 1-30-digit decimals with
# exponents -340..320, exact halfway expansions, edges) as a space-separated list of
# string literals on stdout, the plain strings to VALUES, one per line.
# Usage: python3 gen_decimals.py SEED VALUES > strings.txt
import random, struct, sys
random.seed(int(sys.argv[1]))
vals = []
# random bit patterns, shortest repr
for _ in range(200):
    while True:
        b = random.getrandbits(64)
        f = struct.unpack('<d', struct.pack('<Q', b))[0]
        if f == f and abs(f) != float('inf'): break
    vals.append(repr(f))
# random decimals, many digits
for _ in range(150):
    digits = ''.join(random.choice('0123456789') for _ in range(random.randint(1, 30)))
    frac = ''.join(random.choice('0123456789') for _ in range(random.randint(0, 30)))
    e = random.randint(-340, 320)
    s = digits + ('.' + frac if frac else '') + 'e' + str(e)
    if random.random() < 0.3: s = '-' + s
    vals.append(s)
# edges
vals += ["1.7976931348623157e308", "1.7976931348623158e308", "1.7976931348623159e308", "1e309",
         "2.2250738585072014e-308", "2.2250738585072011e-308", "2.2250738585072012e-308",
         "4.9e-324", "2.4703282292062327e-324", "2.4703282292062328e-324", "1e-325", "0.0", "-0.0",
         "9007199254740993", "9007199254740993.0", "0.30000000000000004", "123456789012345678901234567890.0",
         "2.5e-5", "6.02214076e23", "1e23", "8.98846567431158e307"]
# halfway-ish decimal expansions of doubles: take a double, add half ulp exactly, print 40 digits
from decimal import Decimal, getcontext
getcontext().prec = 800
for _ in range(80):
    while True:
        b = random.getrandbits(63)
        f = struct.unpack('<d', struct.pack('<Q', b))[0]
        if f == f and f != float('inf') and f > 0: break
    import math
    nxt = math.nextafter(f, float('inf'))
    if nxt == float('inf'): continue
    mid = (Decimal(f) + Decimal(nxt)) / 2
    s = format(mid, 'e')
    s2 = format(mid, '.40e')
    vals.append(s)
    vals.append(s2)
print(" ".join('"%s"' % v for v in vals))
open(sys.argv[2], 'w').write("\n".join(vals))
