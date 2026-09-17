import os, re, glob, json, collections, sys
ROOT = "sicp/programs_scm"
TOK = re.compile(r'''\s+|;[^\n]*|#\|.*?\|#|(?P<str>"(?:\\.|[^"\\])*")|(?P<open>[(\[{])|(?P<close>[)\]}])|(?P<q>'|`|,@|,)|(?P<atom>[^\s()\[\]{}";'`,]+)''', re.S)
class Q:  # quoted datum
    def __init__(s, kind, d): s.kind, s.d = kind, d
def read_all(text):
    stack = [[]]; pend = []
    def push(x):
        while pend and pend[-1][1] == len(stack):
            x = Q(pend.pop()[0], x)
        stack[-1].append(x)
    for m in TOK.finditer(text):
        if m.group('open'): stack.append([])
        elif m.group('close'):
            if len(stack) == 1: raise ValueError("unexpected )")
            l = stack.pop(); push(l)
        elif m.group('q'): pend.append((m.group('q'), len(stack)))
        elif m.group('str'): push(('str', m.group('str')))
        elif m.group('atom'): push(m.group('atom'))
    if len(stack) != 1: raise ValueError("unbalanced")
    return stack[0]
NUM = re.compile(r'^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$|^[-+]?\d+/\d+$')
used = collections.defaultdict(set); bound = collections.defaultdict(set); heads = collections.defaultdict(set)
def binders(f, x):
    if isinstance(x, str): bound[f].add(x)
    elif isinstance(x, list):
        for y in x: binders(f, y)
def walk(f, x, qq=0):
    if isinstance(x, Q):
        if x.kind == "'" : return
        if x.kind == '`': return walk(f, x.d, qq+1)
        return walk(f, x.d, qq-1)
    if isinstance(x, tuple): return
    if isinstance(x, str):
        if qq == 0 and not NUM.match(x) and x != '.': used[f].add(x)
        return
    if not x: return
    if qq > 0:
        for y in x: walk(f, y, qq)
        return
    h = x[0]
    if isinstance(h, str): heads[f].add(h)
    if h == 'quote': return
    if h == 'define' and len(x) > 1:
        t = x[1]
        while isinstance(t, list) and t: binders(f, t[1:]); t = t[0]
        binders(f, t)
        for y in x[2:]: walk(f, y)
        return
    if h in ('lambda', 'named-lambda') and len(x) > 1:
        binders(f, x[1]); 
        for y in x[2:]: walk(f, y)
        return
    if h in ('let', 'let*', 'letrec', 'letrec*', 'do') and len(x) > 1:
        rest = x[1:]
        if isinstance(rest[0], str): bound[f].add(rest[0]); rest = rest[1:]
        if rest and isinstance(rest[0], list):
            for b in rest[0]:
                if isinstance(b, list) and b:
                    binders(f, b[0])
                    for y in b[1:]: walk(f, y)
                else: binders(f, b)
        for y in rest[1:]: walk(f, y)
        used[f].add(h)
        return
    for y in x: walk(f, y)
bad = {}
files = sorted(glob.glob(ROOT + "/**/*.scm", recursive=True))
for p in files:
    f = os.path.relpath(p, ROOT)
    try:
        for form in read_all(open(p).read()): walk(f, form)
    except Exception as e:
        bad[f] = str(e)
alldef = collections.Counter()
for f in bound:
    for n in bound[f]: alldef[n] += 1
free = collections.defaultdict(list)
for f in used:
    for n in used[f] - bound[f]: free[n].append(f)
json.dump({"free": free, "bad": bad, "alldef": alldef}, open("free.json", "w"))
print("unreadable:", len(bad)); 
for k, v in bad.items(): print("  ", k, v)
