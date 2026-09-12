import sys, re, collections
sys.path.insert(0, '.')
from analyze import leb_u, sections, import_func_count, code_sizes
def load(wasm):
    b = open(wasm,'rb').read(); secs = {}
    for sid, pl in sections(b): secs.setdefault(sid, pl)
    nimp = import_func_count(secs[2]) if 2 in secs else 0
    return nimp, code_sizes(secs[10])
def order(wat):
    return [int(x) for x in re.findall(r'^ \(func \$(\d+)', open(wat).read(), re.M)]
base_wasm, base_wat, opt_wasm, opt_wat = sys.argv[1:5]
names = {}
if len(sys.argv) > 5:
    for line in open(sys.argv[5]):
        m = re.match(r'\[func-size\] (\d+)\t(\d+)\t(.*)', line)
        if m: names[int(m.group(2))] = m.group(3)
nimp, bs = load(base_wasm); bo = order(base_wat)
nimp2, os_ = load(opt_wasm); oo = order(opt_wat)
assert len(bo) == len(bs), (len(bo), len(bs))
assert len(oo) == len(os_), (len(oo), len(os_))
bsize = dict(zip(bo, bs)); osize = dict(zip(oo, os_))
rows = []
for f in bo:
    rows.append((f, bsize[f], osize.get(f, 0), names.get(f, '?')))
rows.sort(key=lambda r: r[1]-r[2], reverse=True)
tot = sum(r[1]-r[2] for r in rows)
print(f"total code delta {tot} over {len(rows)} funcs (opt has {len(oo)} funcs)")
for r in rows[:int(sys.argv[6]) if len(sys.argv) > 6 else 15]:
    print(f"{r[1]-r[2]:6d}  {r[1]:6d} -> {r[2]:6d}  ({100*(r[1]-r[2])/max(r[1],1):5.1f}%)  {r[3]} [{r[0]}]")
