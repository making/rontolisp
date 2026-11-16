#!/usr/bin/env python3
# prof.py prof.json funcs [top] : self and inclusive samples per wasm function
import json,sys,re,collections
p=json.load(open(sys.argv[1])); names={}
for l in open(sys.argv[2]):
    m=re.match(r'\[func-size\] (\d+)\t(\d+)\t(.*)',l)
    if m: names[int(m.group(2))]=m.group(3)
top=int(sys.argv[3]) if len(sys.argv)>3 else 30
t=p['threads'][0]; sa=t['stringArray']; ft=t['funcTable']['name']; fr=t['frameTable']['func']; st=t['stackTable']
def nm(fi):
    s=sa[ft[fi]]; m=re.match(r'<wasm function (\d+)>',s)
    return names.get(int(m.group(1))+2,s) if m else s
selfc=collections.Counter(); incl=collections.Counter(); n=0
for s in t['samples']['stack']:
    if s is None: continue
    n+=1; seen=set(); first=True
    while s is not None:
        f=nm(fr[st['frame'][s]])
        if first: selfc[f]+=1; first=False
        if f not in seen: incl[f]+=1; seen.add(f)
        s=st['prefix'][s]
print('samples',n)
for f,c in selfc.most_common(top): print(f'{100*c/n:5.1f}% self {100*incl[f]/n:5.1f}% incl  {f}')
