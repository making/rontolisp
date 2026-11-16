#!/usr/bin/env python3
# annot.py asm.txt offsets.txt : objdump lines of one function with the samples that hit each instruction
import sys,re
offs={}
for l in open(sys.argv[2]):
    p=l.split()
    if len(p)==2 and re.fullmatch(r'[0-9a-f]+',p[0]) and p[1].isdigit(): offs[int(p[0],16)]=int(p[1])
lines=[];base=None
for l in open(sys.argv[1]):
    m=re.match(r'\s*([0-9a-f]+):\s',l)
    if m:
        a=int(m.group(1),16)
        if base is None: base=a
        lines.append((a-base,l.rstrip()))
    else: lines.append((None,l.rstrip()))
tot=sum(offs.values()); acc=0
for i,(a,l) in enumerate(lines):
    c=offs.get(a,0) if a is not None else 0
    print(f'{c:5d} {l}' if c else f'      {l}')
print('total',tot,'matched',sum(offs.get(a,0) for a,_ in lines if a is not None))
