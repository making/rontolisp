#!/usr/bin/env python3
# report.py SAMPLES [FUNCS] [top] [--callers] [--offsets NAME]: self and inclusive samples per wasm
# function of a sample.c run under `wasmtime run --profile=perfmap`. FUNCS is the
# stderr of a compile with -Drontolisp.wasm.debug-func-sizes=true (names per index).
import sys,re,bisect,collections
args=[a for a in sys.argv[1:] if not a.startswith('--')]
offn=None
if '--offsets' in sys.argv: offn=sys.argv[sys.argv.index('--offsets')+1]; args=[a for a in args if a!=offn]
lines=open(args[0]).read().split('\n'); pid=lines[0].split()[1]
names={}
if len(args)>1 and args[1]!='-':
    for l in open(args[1]):
        m=re.match(r'\[func-size\] (\d+)\t(\d+)\t(.*)',l)
        if m: names[int(m.group(2))]=m.group(3)
top=int(args[2]) if len(args)>2 else 40
starts=[];ents=[]
for l in open(f'/tmp/perf-{pid}.map'):
    a,s,n=l.rstrip('\n').split(' ',2); a=int(a,16); s=int(s,16)
    m=re.match(r'wasm\[0\]::function\[(\d+)\]',n)
    if m: n=names.get(int(m.group(1)),n)+f'#{m.group(1)}'
    ents.append((a,s,n))
ents.sort(); starts=[e[0] for e in ents]
def look(pc):
    i=bisect.bisect_right(starts,pc)-1
    if i>=0 and pc<ents[i][0]+ents[i][1]: return ents[i]
    return None
selfc=collections.Counter(); incl=collections.Counter(); host=collections.Counter(); offs=collections.Counter(); n=0; pairs=collections.Counter()
for l in lines[1:]:
    if not l.startswith('S '): continue
    a,_,b=l[2:].partition('|'); fr=[int(x,16) for x in a.split()]; stk=[int(x,16) for x in b.split()]
    e=look(fr[0]); chain=[look(x) for x in fr[1:]]
    if e is None:
        # host code: the first JIT return address on the frame chain or the stack
        c=[x for x in chain if x] or [x for x in map(look,stk) if x]
        if not c: continue  # startup, no wasm on the stack
        n+=1; f='[host] under '+c[0][2]; selfc[f]+=1; host[c[0][2]]+=1
        seen=set()
        for x in c:
            if x[2] not in seen: incl[x[2]]+=1; seen.add(x[2])
        continue
    n+=1; selfc[e[2]]+=1
    if offn and offn in e[2]: offs[fr[0]-e[0]]+=1
    seen=set()
    for x in [e]+[c for c in chain if c]:
        if x[2] not in seen: incl[x[2]]+=1; seen.add(x[2])
    if chain and chain[0]: pairs[(e[2],chain[0][2])]+=1
print('samples',n)
for f,c in selfc.most_common(top): print(f'{100*c/n:5.1f}% self {100*incl[f]/n:5.1f}% incl  {f}')
print('-- inclusive')
for f,c in incl.most_common(top): print(f'{100*c/n:5.1f}% incl  {f}')
if '--callers' in sys.argv:
    print('-- leaf <- caller')
    for (f,c),k in pairs.most_common(top): print(f'{100*k/n:5.1f}%  {f} <- {c}')
if offn:
    print('-- offsets in',offn)
    for o,c in sorted(offs.items()): print(f'{o:6x} {c}')
