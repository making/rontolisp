import os, re, glob, sys
sys.setrecursionlimit(10000)
import free as F  # reuses the reader (runs its scan as a side effect)
ROOT, OUT = "sicp/programs_scm", "sicp_shim"
prelude = open("prelude.scm").read()
def show(x):
    if isinstance(x, F.Q): return x.kind + show(x.d)
    if isinstance(x, tuple): return x[1]
    if isinstance(x, str): return x
    return "(" + " ".join(show(y) for y in x) + ")"
def tx(x, delay_ok):
    if isinstance(x, F.Q):
        return x if x.kind == "'" else F.Q(x.kind, tx(x.d, delay_ok))
    if not isinstance(x, list) or not x: return x
    if x[0] == 'quote': return x
    x = [tx(y, delay_ok) for y in x]
    if x[0] == 'cons-stream' and len(x) == 3: return ['cons', x[1], ['shim-memo-proc', ['lambda', [], x[2]]]]
    if x[0] == 'delay' and len(x) == 2 and delay_ok: return ['shim-memo-proc', ['lambda', [], x[1]]]
    return x
n = 0
for p in sorted(glob.glob(ROOT + "/**/*.scm", recursive=True)):
    f = os.path.relpath(p, ROOT); t = open(p).read()
    if re.search(r'^import ', t, re.M) or f in F.bad: continue
    forms = F.read_all(t)
    delay_ok = 'delay' not in F.bound[f]
    body = "\n".join(show(tx(x, delay_ok)) for x in forms)
    # drop prelude definitions the file provides itself
    mine = "\n".join(l for l in re.split(r"\n(?=\(define )", prelude))
    os.makedirs(os.path.dirname(os.path.join(OUT, f)), exist_ok=True)
    exp = "\n".join(re.findall(r";\s*expected:.*", t))
    open(os.path.join(OUT, f), "w").write(mine + "\n;;;; ---- sample ----\n" + body + "\n" + exp + "\n"); n += 1
print("shimmed", n)
