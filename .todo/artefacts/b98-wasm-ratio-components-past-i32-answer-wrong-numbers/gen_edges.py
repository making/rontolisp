# float of ratios aimed at the subnormal and overflow edges (n/d near 2^e for e in
# -1080..-1015, 1010..1030 and -70..70), as one CL program on stdout.
# Usage: python3 gen_edges.py SEED > edges.lisp
import random, sys
random.seed(int(sys.argv[1]))
pairs = []
for _ in range(1500):
    e = random.choice(list(range(-1080, -1015)) + list(range(1010, 1030)) + list(range(-70, 70)) + [-1074, -1075, -1076, -1022, -1023, 1023, 1024])
    nbits = random.choice([1, 2, 10, 31, 53, 54, 60, 64, 100, 200])
    n = random.getrandbits(nbits) | (1 << (nbits - 1))
    if e >= 0:
        d = random.getrandbits(nbits) | (1 << (nbits - 1))
        n = d * (1 << e) + random.choice([0, 1, -1, random.randint(-d, d), d // 2, -(d // 2)])
    else:
        d = n * (1 << (-e)) + random.choice([0, 1, -1, random.randint(-n, n), n // 2])
    if n <= 0: n = 1
    if d <= 0: d = 1
    if random.random() < 0.3: n = -n
    pairs.append((n, d))
print("(defun q (a b) (/ a b))")
print("(defparameter *pairs* '(" + " ".join("(%d %d)" % p for p in pairs) + "))")
print("(dolist (p *pairs*) (print (float (q (first p) (second p)) 1d0)))")
