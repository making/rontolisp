#!/usr/bin/env python3
# sim-mod.py in.wat out.wat FXMOD : `i64.const C; call _fx_mod` with a positive constant C inline (rem_s and the sign fix)
import sys,re
s=open(sys.argv[1]).read(); f=sys.argv[3]; n=0
def rep(m):
    global n
    c=int(m.group(2))
    if c<=0: return m.group(0)
    n+=1; ind=m.group(1)
    return (f"{ind}i64.const {c}\n{ind}i64.rem_s\n{ind}local.tee $modtmp\n{ind}local.get $modtmp\n{ind}i64.const 63\n{ind}i64.shr_s\n{ind}i64.const {c}\n{ind}i64.and\n{ind}i64.add\n")
s2=re.sub(r'([ \t]*)i64\.const (-?\d+)\n[ \t]*call \$#func%s\n'%f, rep, s)
# add the temp local to every function that uses it
out=[]; 
for part in re.split(r'(?=\n  \(func )', s2):
    if '$modtmp' in part:
        i=part.index('\n',1)  # end of func header line
        part=part[:i]+'\n    (local $modtmp i64)'+part[i:]
    out.append(part)
open(sys.argv[2],'w').write(''.join(out)); print('sites',n)
