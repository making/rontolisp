"""Reference for examples/llama2/deltanet-check.lisp: a transcription of
transformers' modeling_qwen3_5.py (read 2026-09-03) -- torch_recurrent_gated_delta_rule,
causal_conv1d_update, l2norm, Qwen3_5RMSNormGated and Qwen3_5GatedDeltaNet.forward --
in plain float64 Python, over pseudo-random inputs from a fixed LCG. Prints the Lisp
literals for the inputs and the expected outputs at 3 decimals."""
import math

class Lcg:
    def __init__(self, seed):
        self.s = seed
    def next(self):
        self.s = (self.s * 6364136223846793005 + 1442695040888963407) % (1 << 64)
        return (self.s >> 33) / float(1 << 31)          # [0, 1)
    def unit(self):
        # two decimals in [-1, 1): exact as a decimal literal, short to embed
        return round(self.next() * 2.0 - 1.0, 2)
    def vec(self, n):
        return [self.unit() for _ in range(n)]
    def mat(self, rows, cols):
        return [self.vec(cols) for _ in range(rows)]

def l2norm(x, eps=1e-6):
    inv = 1.0 / math.sqrt(sum(v * v for v in x) + eps)
    return [v * inv for v in x]

def sigmoid(x):
    return 1.0 / (1.0 + math.exp(-x))

def silu(x):
    return x * sigmoid(x)

def softplus(x):
    return x if x > 20 else math.log1p(math.exp(x))

def matvec(m, x):
    return [sum(r[i] * x[i] for i in range(len(x))) for r in m]

def recurrent_gated_delta_rule(q, k, v, g, beta, state):
    """One token, one head: torch_recurrent_gated_delta_rule's loop body with
    use_qk_l2norm_in_kernel=True. state is S[i][j] (k_dim x v_dim), updated in place.
    Returns the output o (v_dim)."""
    kd, vd = len(q), len(v)
    q = l2norm(q)
    k = l2norm(k)
    q = [x / math.sqrt(kd) for x in q]
    decay = math.exp(g)
    for i in range(kd):
        for j in range(vd):
            state[i][j] *= decay
    kv = [sum(state[i][j] * k[i] for i in range(kd)) for j in range(vd)]
    delta = [(v[j] - kv[j]) * beta for j in range(vd)]
    for i in range(kd):
        for j in range(vd):
            state[i][j] += k[i] * delta[j]
    return [sum(state[i][j] * q[i] for i in range(kd)) for j in range(vd)]

MARGIN = 2e-5

def fmt(xs):
    # every shown value must sit at least MARGIN from a 3-decimal rounding
    # boundary and from zero (a sign flip changes the text) -- reseed otherwise
    for x in xs:
        frac = abs(x * 1000.0) % 1.0
        if abs(frac - 0.5) < MARGIN * 1000.0 or abs(x) < MARGIN:
            raise SystemExit("value %r too close to a rounding boundary" % x)
    return "".join(" %.3f" % x for x in xs)

def lit(xs):
    return "#f(" + " ".join(("%.2f" % x) for x in xs) + ")"

def part1(seed=681):
    """The recurrence alone: heads 2, dim 4, 3 tokens."""
    rng = Lcg(seed)
    H, D, T = 2, 4, 3
    print(";; part 1 inputs")
    states = [[[0.0] * D for _ in range(D)] for _ in range(H)]
    outs = []
    lisp_in = []
    for t in range(T):
        for h in range(H):
            q, k, v = rng.vec(D), rng.vec(D), rng.vec(D)
            g = -round(rng.next() * 2.0, 2)          # in (-2, 0]
            beta = round(rng.next(), 2)              # in [0, 1)
            lisp_in.append((t, h, q, k, v, g, beta))
            o = recurrent_gated_delta_rule(q, k, v, g, beta, states[h])
            outs.append((t, h, o))
    for (t, h, q, k, v, g, beta) in lisp_in:
        print("(list %s %s %s %.2f %.2f)" % (lit(q), lit(k), lit(v), g, beta))
    print(";; part 1 expected")
    for (t, h, o) in outs:
        print("t=%d h=%d o=%s" % (t, h, fmt(o)))
    for h in range(H):
        for j in range(D):
            # the engine stores S transposed (v x k): row j holds state[.][j]
            print("h=%d S^T[%d]=%s" % (h, j, fmt([states[h][i][j] for i in range(D)])))
    return outs, states

def gated_deltanet_layer(dim, H, kd, vd, K, rng):
    key_dim, value_dim = kd * H, vd * H
    conv_dim = 2 * key_dim + value_dim
    return dict(
        wqkv=rng.mat(conv_dim, dim),
        wz=rng.mat(value_dim, dim),
        wb=rng.mat(H, dim),
        wa=rng.mat(H, dim),
        conv=rng.mat(conv_dim, K),
        a=[-round(0.5 + rng.next() * 2.0, 2) for _ in range(H)],   # -exp(A_log), in (-2.5, -0.5]
        dt_bias=rng.vec(H),
        gnorm=[round(0.5 + rng.next(), 2) for _ in range(vd)],
        wo=rng.mat(dim, value_dim),
    )

def gated_deltanet_forward(layer, st, x, eps, H, kd, vd, K):
    """Qwen3_5GatedDeltaNet.forward for one token under a cache: st = dict(conv=list of
    the previous K-1 qkv vectors, S=per-head states)."""
    key_dim, value_dim = kd * H, vd * H
    qkv = matvec(layer["wqkv"], x)
    z = matvec(layer["wz"], x)
    b = matvec(layer["wb"], x)
    a = matvec(layer["wa"], x)
    # causal_conv1d_update: window = [prev K-1 ..., current]; out[c] = sum_k w[c][k] * window[k][c]
    window = st["conv"] + [qkv]
    conv = [sum(layer["conv"][c][k] * window[k][c] for k in range(K)) for c in range(len(qkv))]
    st["conv"] = window[1:]
    xc = [silu(v) for v in conv]
    out = []
    for h in range(H):
        q = xc[h * kd:(h + 1) * kd]
        k = xc[key_dim + h * kd: key_dim + (h + 1) * kd]
        v = xc[2 * key_dim + h * vd: 2 * key_dim + (h + 1) * vd]
        beta = sigmoid(b[h])
        g = layer["a"][h] * softplus(a[h] + layer["dt_bias"][h])
        o = recurrent_gated_delta_rule(q, k, v, g, beta, st["S"][h])
        # Qwen3_5RMSNormGated: norm, weight, then * silu(z)
        var = sum(t * t for t in o) / vd
        o = [t / math.sqrt(var + eps) for t in o]
        zh = z[h * vd:(h + 1) * vd]
        o = [o[i] * layer["gnorm"][i] * silu(zh[i]) for i in range(vd)]
        out.extend(o)
    return matvec(layer["wo"], out)

def part2(seed=3579):
    rng = Lcg(seed)
    dim, H, kd, vd, K, T = 8, 2, 4, 4, 4, 5
    layer = gated_deltanet_layer(dim, H, kd, vd, K, rng)
    xs = [rng.vec(dim) for _ in range(T)]
    print(";; part 2 weights")
    for name in ("wqkv", "wz", "wb", "wa", "conv", "wo"):
        m = layer[name]
        print("(%s %d %d %s)" % (name, len(m), len(m[0]), lit([v for r in m for v in r])))
    for name in ("a", "dt_bias", "gnorm"):
        print("(%s %s)" % (name, lit(layer[name])))
    print(";; part 2 inputs")
    for x in xs:
        print(lit(x))
    print(";; part 2 expected")
    st = dict(conv=[[0.0] * (2 * kd * H + vd * H) for _ in range(K - 1)], S=[[[0.0] * vd for _ in range(kd)] for _ in range(H)])
    for t, x in enumerate(xs):
        y = gated_deltanet_forward(layer, st, x, 1e-6, H, kd, vd, K)
        print("t=%d y=%s" % (t, fmt(y)))

if __name__ == "__main__":
    import sys
    part1(int(sys.argv[1]) if len(sys.argv) > 1 else 681)
    part2(int(sys.argv[2]) if len(sys.argv) > 2 else 3579)
