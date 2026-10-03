# float of ratios exactly halfway between two doubles (normal and subnormal range),
# and one sticky bit off them, as one CL program on stdout.
# Usage: python3 gen_ties.py SEED > ties.lisp
import random, sys
random.seed(int(sys.argv[1]))
pairs = []
for _ in range(1000):
    kind = random.choice(['normal-tie', 'sub-tie', 'normal-near', 'sub-near'])
    if kind.startswith('normal'):
        e = random.randint(-1022, 1023)
        m = (1 << 53) + random.getrandbits(53) * 2  # even 54-bit numerator over 2 => tie at 53 bits
        m = random.getrandbits(52) | (1 << 52)
        num = 2 * m + 1                     # (2m+1)/2 * 2^(e-52): exactly halfway between m and m+1 ulps
        sh = e - 53
        if kind == 'normal-near':
            num = num * 1024 + random.choice([-1, 1])
            sh -= 10
        if sh >= 0:
            n, d = num << sh, 1
        else:
            n, d = num, 1 << (-sh)
        # scramble with a common odd factor so the ratio normalizes back (exercises gcd)
        f = random.getrandbits(40) | 1
        n, d = n * f, d * f
    else:
        j = random.getrandbits(52)
        num = 2 * j + 1
        n, d = num, 1 << 1075
        if kind == 'sub-near':
            n, d = num * 1024 + random.choice([-1, 1]), 1 << 1085
    if random.random() < 0.5: n = -n
    pairs.append((n, d))
print("(defun q (a b) (/ a b))")
print("(defparameter *pairs* '(" + " ".join("(%d %d)" % p for p in pairs) + "))")
print("(dolist (p *pairs*) (print (float (q (first p) (second p)) 1d0)))")
