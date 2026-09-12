import sys, re, collections
def instrs(wat):
    """(func index, [instruction strings]) per function from a wasm-tools print."""
    out = []; cur = None; body = []
    for line in wat.splitlines():
        m = re.match(r'\s*\(func \(;(\d+);\)', line)
        if m:
            if cur is not None: out.append((cur, body))
            cur = int(m.group(1)); body = []; continue
        if cur is None: continue
        s = line.strip()
        if not s or s.startswith('(local') or s.startswith(')') or s.startswith('(export') or s.startswith('(data'): 
            if s.startswith(')') and cur is not None: out.append((cur, body)); cur = None; body = []
            continue
        s = re.sub(r';;.*$', '', s).strip()
        body.append(s)
    if cur is not None: out.append((cur, body))
    return out
def count(wat):
    c = collections.Counter()
    for f, b in instrs(wat):
        n = len(b)
        for i in range(n):
            s = b[i]; nx = b[i+1] if i+1 < n else ''; nx2 = b[i+2] if i+2 < n else ''; nx3 = b[i+3] if i+3 < n else ''
            if s.startswith('local.set ') and nx == 'local.get ' + s.split()[1]:
                c['P1 set N; get N -> tee (2B)'] += 1
            if s.startswith('local.tee ') and nx == 'drop':
                c['P2 tee N; drop -> set (1B)'] += 1
            if nx == 'drop' and (s.startswith('ref.null') or s.startswith('i32.const') or s.startswith('local.get') or s.startswith('i64.const')):
                c['P3 pure value; drop (2-3B)'] += 1
            if s.startswith('local.get ') and nx.startswith('local.set ') and nx2 == 'local.get ' + nx.split()[1]:
                c['P4 get A; set B; get B (copy)'] += 1
            if s.startswith('br 0') and nx == 'end' and nx2 == 'unreachable' and nx3 == 'end':
                c['P5 loop tail unreachable (1B)'] += 1
            if s.startswith('struct.new ') and nx.startswith('ref.cast') and nx2.startswith('struct.get '):
                c['P6 literal box then unbox'] += 1
            if s.startswith('if (result eqref)') and nx.startswith('call ') and nx2 == 'else' and nx3 == 'ref.null eq' and i+5 < n and b[i+4] == 'end' and b[i+5] == 'ref.is_null':
                c['P7 if(result) call E else nil end; is_null'] += 1
            if s.startswith('ref.i31') and nx.startswith('ref.cast (ref i31)') and nx2 == 'i31.get_s':
                c['P10 ref.i31; cast; i31.get_s (4B)'] += 1
            if s.startswith('ref.test') and nx == 'i32.eqz' and nx2.startswith('if (result eqref)') and nx3.startswith('call '):
                c['P8 keyword-loop head (consp check)'] += 1
            if s.startswith('br_table'):
                labels = s.split()[1:]
                dflt = labels[-1]
                c['P12 br_table default labels (~1B each)'] += sum(1 for l in labels[:-1] if l == dflt)
                c['P12b br_table live labels'] += sum(1 for l in labels[:-1] if l != dflt)
            if s.startswith('local.set ') and nx.startswith('local.set ') and nx2 == 'local.get ' + nx.split()[1] and nx3 == 'local.get ' + s.split()[1]:
                c['P13 set b; set a; get a; get b (tee-able 2B)'] += 1
            if s == 'ref.is_null' and nx.startswith('if (result eqref)') and nx2 == 'ref.null eq' and nx3 == 'else':
                c['P14 is_null; if nil else X end (safe accessor)'] += 1
    return c
for f in sys.argv[1:]:
    print('==', f)
    for k, v in sorted(count(open(f).read()).items()): print(f"  {k}: {v}")
