# float of limb integers (64..1100 bits; ties, near ties, the overflow edge) as one CL
# program on stdout; the values go to VALUES, one per line, for a check against
# Python's float(int).
# Usage: python3 gen_bigint.py SEED VALUES > bigint.lisp
import random, sys
random.seed(int(sys.argv[1]))
vals = []
for _ in range(1500):
    kind = random.choice(['rand', 'tie', 'near', 'edge'])
    L = random.choice(list(range(64, 130)) + list(range(1000, 1030)) + [random.randint(64, 1100)])
    if kind == 'rand':
        v = random.getrandbits(L) | (1 << (L - 1))
    elif kind == 'tie':
        m = random.getrandbits(52) | (1 << 52)
        sh = max(L - 54, 1)
        v = ((2 * m + 1) << (sh - 1))
    elif kind == 'near':
        m = random.getrandbits(52) | (1 << 52)
        sh = max(L - 54, 2)
        v = ((2 * m + 1) << (sh - 1)) + random.choice([-1, 1])
    else:
        v = random.choice([(1 << 1024) - (1 << 970), (1 << 1024) - (1 << 970) - 1, (1 << 1024) - (1 << 971), (1 << 63), (1 << 64) - 1, (1 << 64) + 1, (1 << 1023)])
    if random.random() < 0.4: v = -v
    vals.append(v)
print("(defun g (x) (float x 1d0))")
print("(defparameter *vals* '(" + " ".join(str(v) for v in vals) + "))")
print("(dolist (v *vals*) (print (g v)))")
open(sys.argv[2], 'w').write("\n".join(str(v) for v in vals))
