import re,sys
src=open(sys.argv[1]).read().split('\n')
out=[];lits={};i=0
nglob=sum(1 for l in src if l.startswith('  (global'))
last_global=max(k for k,l in enumerate(src) if l.startswith('  (global'))
while i<len(src):
    l=src[i]
    if l.strip()=='call 49' and len(out)>=2 and out[-1].strip().startswith('i32.const') and out[-2].strip().startswith('i32.const'):
        ln=out.pop().strip().split()[1]; off=out.pop().strip().split()[1]
        g=lits.setdefault((off,ln),nglob+len(lits))
        out.append(l.replace('call 49','global.get %d'%g))
    else: out.append(l)
    i+=1
# insert globals and init in func 2
res=[]
for k,l in enumerate(out):
    res.append(l)
    if l.startswith('  (global') and out[k+1].startswith('  (global')==False:
        for _ in lits: res.append('  (global (mut eqref) ref.null eq)')
final=[];infunc=False
for k,l in enumerate(res):
    final.append(l)
    if re.match(r'  \(func \(;2;\)',l):
        infunc=True; continue
    if infunc:
        # after locals line(s)
        nxt=res[k+1] if k+1<len(res) else ''
        if not nxt.strip().startswith('(local') :
            for (off,ln),g in lits.items():
                final += ['    i32.const '+off,'    i32.const '+ln,'    call 48','    global.set %d'%g]
            infunc=False
open(sys.argv[2],'w').write('\n'.join(final))
print(len(lits),'literals')
