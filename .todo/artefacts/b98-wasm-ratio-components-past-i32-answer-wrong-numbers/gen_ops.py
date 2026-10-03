# Random ratio construction, arithmetic, rounding and comparison (components up to
# 300 bits) plus rational of random doubles, as one CL program on stdout.
# Usage: python3 gen_ops.py SEED > ops.lisp
import random, struct, sys
random.seed(int(sys.argv[1]) if len(sys.argv) > 1 else 1)
def rint(maxbits):
    bits = random.choice([1,2,5,10,20,29,30,31,32,33,40,52,53,54,62,63,64,65,70,100,128,200,300])
    bits = min(bits, maxbits)
    v = random.getrandbits(bits) | (1 << (bits-1)) if bits>0 else 0
    if random.random() < 0.5: v = -v
    return v
def rpos(maxbits):
    v = 0
    while v == 0:
        v = abs(rint(maxbits))
    return v
pairs = [(rint(300), rpos(300)) for _ in range(400)]
# magnitude-targeted pairs for float conversion edges
for _ in range(300):
    e = random.choice(list(range(-1080,-1015)) + list(range(1015, 1030)) + list(range(-60, 60)))
    d = rpos(random.choice([10,30,60,100,200]))
    # n ~ d * 2^e
    if e >= 0:
        n = d * (1 << e) + random.randint(-d, d)
    else:
        n = (d >> (-e)) + random.randint(0, 3)
        if n == 0:
            n = 1
            d = d
    if random.random() < 0.3: n = -n
    pairs.append((n, d))
lines = []
lines.append("(defun q (a b) (/ a b))")
lines.append("(defparameter *pairs* '(" + " ".join("(%d %d)" % p for p in pairs) + "))")
lines.append("""
(dolist (p *pairs*)
  (let ((r (q (first p) (second p))))
    (print r)
    (print (float r 1d0))
    (print (list (floor r) (ceiling r) (round r) (truncate r)))))
(let ((prev 1))
  (dolist (p *pairs*)
    (let ((r (q (first p) (second p))))
      (print (list (+ prev r) (- prev r) (* prev r) (if (zerop r) 0 (q prev r)) (< prev r) (= prev r)))
      (setq prev r))))
""")
# doubles: random bit patterns
dbls = []
for _ in range(300):
    while True:
        b = random.getrandbits(64)
        f = struct.unpack('<d', struct.pack('<Q', b))[0]
        if f == f and abs(f) != float('inf'):
            break
    dbls.append(repr(f).replace('e', 'd') if 'e' in repr(f) else repr(f) + 'd0')
lines.append("(defparameter *dbls* '(" + " ".join(dbls) + "))")
lines.append("""
(dolist (x *dbls*)
  (let ((r (rational x)))
    (print r)
    (print (= (float r 1d0) x))
    (print (list (< x (q 1 3)) (= x r) (> x (+ r (q 1 (expt 2 1100))))))))
""")
print("\n".join(lines))
