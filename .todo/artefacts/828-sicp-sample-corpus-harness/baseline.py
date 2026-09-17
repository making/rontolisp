import json, re, collections
d = json.load(open("free.json")); r0 = json.load(open("results.json")); r1 = json.load(open("results_shim.json")); rb = json.load(open("results_backends.json"))
known = set(open("known.txt").read().split())
syntax = set("define lambda let let* letrec letrec* do if cond case and or when unless begin set! quote quasiquote unquote unquote-splicing else => import define-record-type let-values define-values #f #t".split())
planned = set(re.findall(r"^\(define \(?([^\s()]+)", open("prelude.scm").read(), re.M)) | set("cons-stream delay force read eval user-initial-environment parallel-execute test-and-set! exit".split())
planned = {n for n in planned if not n.startswith("shim-")}
R = "sicp/programs_scm/"
freeof = collections.defaultdict(set)
for n, fs in d["free"].items():
    for f in fs: freeof[f].add(n)
def category(f):
    t = open(R + f).read()
    if re.search(r"^import ", t, re.M): return "js-import"
    if f in d["bad"]: return "unreadable"
    if "variant=non-det" in t: return "embedded-amb"
    if "variant=lazy" in t: return "embedded-lazy"
    if f.startswith("chapter4/section4/subsection1"): return "embedded-query"
    missing = freeof[f] - known - syntax - planned
    if "variant=concurrent" in t: return "concurrent" if not (missing - {"parallel-execute"}) else "fragment"
    return "fragment" if missing else "scheme"
def st(v): return "ok" if v == 0 else str(v)
rows = []; c = collections.Counter()
for f in sorted(r0):
    cat = category(f)
    now = st(r0[f]["file"]["exit"]); shim = st(r1[f]["file"]["exit"]) if f in r1 else "-"
    jvm = wasm = "-"
    if f in rb:
        io = rb[f]["interp"][1]
        def b(x): return "ok" if x[0] == 0 and x[1] == io else ("differs" if x[0] == 0 else str(x[0]))
        jvm, wasm = b(rb[f]["jvm"]), b(rb[f]["wasm"])
    needs = ",".join(sorted(freeof[f] & planned))
    rows.append((f, cat, now, shim, jvm, wasm, needs)); c[(cat, now, shim)] += 1
open("baseline.tsv", "w").write("file\tcategory\tinterpreter\tinterpreter+shim\tjvm+shim\twasm+shim\tmissing-names-used\n" + "\n".join("\t".join(x) for x in rows) + "\n")
cats = collections.Counter(x[1] for x in rows); print(cats)
for k, n in sorted(c.items()): print(k, n)
print("scheme/concurrent files not ok after shim:")
for x in rows:
    if x[1] in ("scheme", "concurrent") and (x[3] != "ok" or x[4] not in ("ok",) or x[5] != "ok"): print("  ", x)
