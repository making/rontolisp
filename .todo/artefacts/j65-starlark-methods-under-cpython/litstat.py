import re,collections,sys,subprocess
wat=subprocess.run(['wasm-tools','print',sys.argv[1]],capture_output=True,text=True).stdout.split('\n')
raw=open(sys.argv[1],'rb').read()
start=[l for l in wat if l.strip().startswith('(start')]
if not start: print('no start'); sys.exit()
f=int(re.findall(r'\d+',start[0])[0])
i=[k for k,l in enumerate(wat) if re.match(r'  \(func \(;%d;\)'%f,l)][0]
lits={}
k=i+1
while not wat[k].startswith('  )') and not wat[k].startswith('  (func'):
    if wat[k].strip().startswith('global.set'):
        g=int(wat[k].split()[1]); off=int(wat[k-3].split()[1]); ln=int(wat[k-2].split()[1]); lits[g]=(off,ln)
    k+=1
cnt=collections.Counter(); where=collections.defaultdict(list); cur=None
for l in wat:
    m=re.match(r'  \(func \(;(\d+);\)',l)
    if m: cur=int(m.group(1))
    m=re.match(r'\s+global.get (\d+)$',l)
    if m and int(m.group(1)) in lits: cnt[int(m.group(1))]+=1; where[int(m.group(1))].append(cur)
h=collections.Counter(min(c,5) for c in cnt.values())
print(len(lits),'literals; sites histogram',sorted(h.items()))
# data segments: find text
datas=[(int(m.group(1)),m.group(2)) for m in re.finditer(r'\(data \(;\d+;\) \(i32.const (\d+)\) "((?:[^"\\]|\\.)*)"\)', '\n'.join(wat))]
def text(off,ln):
    for base,sv in datas:
        b=bytes(sv,'latin1').decode('unicode_escape').encode('latin1') if False else None
    return ''
if len(sys.argv)>2:
    for g,c in sorted(cnt.items(), key=lambda x:x[1]):
        if c==1: print(g,lits[g],where[g])
