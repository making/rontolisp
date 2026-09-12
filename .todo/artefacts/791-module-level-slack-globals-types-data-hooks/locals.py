import sys, re, collections
from patterns import instrs
for f in sys.argv[1:]:
    wat = open(f).read()
    # locals per function: params + declared locals
    nloc = {}; cur=None
    for line in wat.splitlines():
        m = re.match(r'\s*\(func \(;(\d+);\)(.*)', line)
        if m:
            cur = int(m.group(1)); nloc[cur] = len(re.findall(r'\(param', m.group(2))) and sum(len(x.split()) for x in re.findall(r'\(param ([^)]*)\)', m.group(2))) or 0
            continue
        m = re.match(r'\s*\(local ([^)]*)\)', line)
        if m and cur is not None: nloc[cur] += len(m.group(1).split())
    big = [k for k,v in nloc.items() if v > 128]
    wide = 0; total = 0; accvar = 0; acc = 0; boxinit = 0
    for fi, b in instrs(wat):
        n = len(b)
        for i, s in enumerate(b):
            if s.startswith('local.'):
                total += 1
                if int(s.split()[1]) >= 128: wide += 1
            nx = b[i+1] if i+1<n else ''; nx2 = b[i+2] if i+2<n else ''; nx3 = b[i+3] if i+3<n else ''; nx4 = b[i+4] if i+4<n else ''
            if s == 'ref.is_null' and nx.startswith('if (result eqref)') and nx2 == 'ref.null eq' and nx3 == 'else':
                acc += 1
                if i >= 3 and b[i-1].startswith('local.get') and b[i-2].startswith('local.set') and b[i-3].startswith('local.get') and b[i-1].split()[1] == b[i-2].split()[1]:
                    accvar += 1
            if s.startswith('ref.null') and nx.startswith('struct.new ') and nx2.startswith('local.set'):
                boxinit += 1
    print(f"{f}: funcs={len(nloc)} funcs>128 locals={len(big)} local.* ops={total} of which index>=128: {wide} | accessor shapes={acc} with plain-variable operand={accvar} | boxinit={boxinit}")
