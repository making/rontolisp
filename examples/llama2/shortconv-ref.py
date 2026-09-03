"""Reference for shortconv-check.lisp: transformers' modeling_lfm2.py
Lfm2ShortConv.forward under a cache (read 2026-09-03) -- in_proj, the B | C | x
split, h = B * x, causal_conv1d_update of kernel L with no activation, y = C * conv,
out_proj -- in plain float64 Python over pseudo-random inputs from a fixed LCG.
Prints the Lisp literals for the weights and inputs and the expected lines at 3
decimals; every shown value is at least MARGIN from a rounding boundary and from
zero, so single-float arithmetic lands on the same text (reseed otherwise)."""
import math
import sys

class Lcg:
    def __init__(self, seed):
        self.s = seed
    def next(self):
        self.s = (self.s * 6364136223846793005 + 1442695040888963407) % (1 << 64)
        return (self.s >> 33) / float(1 << 31)          # [0, 1)
    def unit(self):
        return round(self.next() * 2.0 - 1.0, 2)         # two decimals in [-1, 1)
    def vec(self, n):
        return [self.unit() for _ in range(n)]
    def mat(self, rows, cols):
        return [self.vec(cols) for _ in range(rows)]

MARGIN = 2e-5

def fmt(xs):
    for x in xs:
        frac = abs(x * 1000.0) % 1.0
        if abs(frac - 0.5) < MARGIN * 1000.0 or abs(x) < MARGIN:
            raise SystemExit("value %r too close to a rounding boundary" % x)
    return "".join(" %.3f" % x for x in xs)

def lit(xs):
    return "#f(" + " ".join(("%.2f" % x) for x in xs) + ")"

def matvec(m, x):
    return [sum(r[i] * x[i] for i in range(len(x))) for r in m]

def short_conv_forward(layer, st, x, dim, L):
    """Lfm2ShortConv.forward for one token: st['conv'] holds the previous L-1 gated
    inputs, oldest first."""
    bcx = matvec(layer["in_proj"], x)
    B, C, xx = bcx[:dim], bcx[dim:2 * dim], bcx[2 * dim:]
    h = [B[i] * xx[i] for i in range(dim)]
    window = st["conv"] + [h]
    conv = [sum(layer["conv"][c][k] * window[k][c] for k in range(L)) for c in range(dim)]
    st["conv"] = window[1:]
    y = [C[i] * conv[i] for i in range(dim)]
    return matvec(layer["out_proj"], y)

def main(seed=678):
    rng = Lcg(seed)
    dim, L, T = 8, 3, 4
    layer = dict(in_proj=rng.mat(3 * dim, dim), conv=rng.mat(dim, L), out_proj=rng.mat(dim, dim))
    xs = [rng.vec(dim) for _ in range(T)]
    print(";; weights")
    for name in ("in_proj", "conv", "out_proj"):
        m = layer[name]
        print("(%s %d %d %s)" % (name, len(m), len(m[0]), lit([v for r in m for v in r])))
    print(";; inputs")
    for x in xs:
        print(lit(x))
    print(";; expected")
    st = dict(conv=[[0.0] * dim for _ in range(L - 1)])
    for t, x in enumerate(xs):
        print("t=%d y=%s" % (t, fmt(short_conv_forward(layer, st, x, dim, L))))

if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 678)
