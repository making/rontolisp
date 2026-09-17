import json, re, collections
r = json.load(open("results.json"))
R = "sicp/programs_scm/"
TOK = re.compile(r'\s+|(?P<str>"(?:\\.|[^"\\])*")|(?P<o>\()|(?P<c>\))|(?P<a>[^\s()"]+)')
def parse(s):
    toks = [m for m in TOK.finditer(s) if m.lastgroup]
    pos = 0
    def rd():
        nonlocal pos
        m = toks[pos]; pos += 1
        if m.group('o'):
            items = []; tail = "null"
            while not toks[pos].group('c'):
                if toks[pos].group('a') == '.':
                    pos += 1; tail = rd()
                else: items.append(rd())
            pos += 1
            for it in reversed(items): tail = "[%s,%s]" % (it, tail)
            return tail
        if m.group('str'): return "'" + m.group('str')[1:-1] + "'"
        a = m.group('a')
        if a == '#t': return 'true'
        if a == '#f': return 'false'
        try: return num(a)
        except ValueError: return "'" + a + "'"
    return rd()
def num(a):
    if '/' in a:
        p, q = a.split('/'); a = str(int(p) / int(q))
    return repr(round(float(a), 9))
def normexp(e):
    e = re.sub(r"\s+", "", e)
    e = e.replace('"', "'")
    return re.sub(r"(?<![\w'.])-?\d+\.?\d*(e[-+]?\d+)?(?![\w'])", lambda m: num(m.group(0)), e)
res = collections.defaultdict(list)
for k, v in sorted(r.items()):
    t = open(R + k).read()
    m = re.search(r";\s*expected:\s*(.*)", t)
    if not m: continue
    out = v["repl"]["out"].replace("scheme> ", "")
    lines = [l for l in out.splitlines() if l.strip()]
    if any(l.startswith("Error:") for l in lines): res["error"].append(k); continue
    if v["repl"]["exit"] != 0: res["exit"].append(k); continue
    last = lines[-1] if lines else ""
    try: got = parse(last)
    except Exception: got = "<unparsed:%s>" % last[:60]
    want = normexp(m.group(1))
    if got == want: res["ok"].append(k)
    else: res["mismatch"].append((k, m.group(1)[:70], last[:70]))
for c in res: print(c, len(res[c]))
for x in res["mismatch"]: print(x)
json.dump(res, open("expected.json", "w"), indent=1)
