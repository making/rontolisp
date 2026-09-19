import sys, os
def tokenize(s):
    i=0; n=len(s); out=[]
    while i<n:
        c=s[i]
        if c.isspace(): i+=1; continue
        if c==';':
            while i<n and s[i]!='\n': i+=1
            continue
        if s.startswith('#|',i):
            j=s.find('|#',i); i=n if j<0 else j+2; continue
        if c in '()[]': out.append('(' if c in '([' else ')'); i+=1; continue
        if c in "'`": out.append(c); i+=1; continue
        if c==',':
            if i+1<n and s[i+1]=='@': out.append(',@'); i+=2
            else: out.append(','); i+=1
            continue
        if c=='"':
            j=i+1
            while j<n and s[j]!='"':
                j+= 2 if s[j]=='\\' else 1
            out.append(('str',s[i:j+1])); i=j+1; continue
        if s.startswith('#\\',i):
            j=i+3
            while j<n and not s[j].isspace() and s[j] not in '()[]': j+=1
            out.append(('chr',s[i:j])); i=j; continue
        if s.startswith('#(',i): out.append("'"); out.append('('); i+=2; continue
        j=i
        while j<n and not s[j].isspace() and s[j] not in '()[]";': j+=1
        out.append(s[i:j].lower()); i=j
    return out
def parse(toks):
    pos=[0]
    def rd():
        t=toks[pos[0]]; pos[0]+=1
        if t=='(':
            l=[]
            while pos[0]<len(toks) and toks[pos[0]]!=')': l.append(rd())
            pos[0]+=1; return l
        if t in ("'", '`', ',', ',@'): return ['quote', rd()] if t=="'" else ['quasi', rd()]
        return t
    forms=[]
    while pos[0]<len(toks):
        if toks[pos[0]]==')': pos[0]+=1; continue
        forms.append(rd())
    return forms
isl=lambda x: isinstance(x,list)
def tails(e, acc):
    if not isl(e) or not e: return
    h=e[0]
    if h=='if':
        for b in e[2:4]: tails(b,acc)
    elif h in ('begin','when','unless'):
        if len(e)>1 and (h=='begin' or len(e)>2): tails(e[-1],acc)
    elif h in ('let','let*','letrec','letrec*'):
        if len(e)>2 and isinstance(e[1],str): body(e[3:],acc)
        elif len(e)>2: body(e[2:],acc)
    elif h=='cond':
        for cl in e[1:]:
            if isl(cl) and len(cl)>1 and cl[1]!='=>': tails(cl[-1],acc)
    elif h=='case':
        for cl in e[2:]:
            if isl(cl) and len(cl)>1: tails(cl[-1],acc)
    elif h in ('and','or'):
        if len(e)>1: tails(e[-1],acc)
    elif isinstance(h,str) and h not in ('quote','quasi','lambda','define','set!','do','delay','cons-stream'):
        acc.add(h)
def body(forms,acc):
    forms=[f for f in forms if not (isl(f) and f and f[0]=='define')]
    if forms: tails(forms[-1],acc)
def lam_of(d):
    if len(d)>=3 and isl(d[1]) and d[1] and isinstance(d[1][0],str): return d[1][0], d[2:]
    if len(d)==3 and isinstance(d[1],str) and isl(d[2]) and d[2] and d[2][0]=='lambda': return d[1], d[2][2:]
    return None
def cycles(defs):
    g={}
    for n,b in defs.items():
        a=set(); body(b,a); g[n]={m for m in a if m in defs}
    idx={};low={};st=[];on=set();res=[];c=[0]
    def sc(v):
        idx[v]=low[v]=c[0];c[0]+=1;st.append(v);on.add(v)
        for w in g[v]:
            if w not in idx: sc(w); low[v]=min(low[v],low[w])
            elif w in on: low[v]=min(low[v],idx[w])
        if low[v]==idx[v]:
            comp=[]
            while True:
                w=st.pop();on.discard(w);comp.append(w)
                if w==v:break
            if len(comp)>1: res.append(comp)
    for v in g:
        if v not in idx: sc(v)
    return res
found=[]
def walk(e):
    if not isl(e) or not e: return
    bodies=[]
    if e[0]=='define' and len(e)>2 and isl(e[1]): bodies.append(e[2:])
    if e[0]=='lambda' and len(e)>2: bodies.append(e[2:])
    if e[0] in ('let','let*','letrec','letrec*') and len(e)>2:
        bodies.append(e[3:] if isinstance(e[1],str) else e[2:])
    for b in bodies:
        defs={}
        for f in b:
            if isl(f) and f and f[0]=='define':
                r=lam_of(f)
                if r: defs[r[0]]=r[1]
        for cyc in (cycles(defs) if defs else []): found.append(('define',cyc))
    if e[0] in ('letrec','letrec*') and len(e)>2 and isl(e[1]):
        defs={}
        for bnd in e[1]:
            if isl(bnd) and len(bnd)==2 and isl(bnd[1]) and bnd[1] and bnd[1][0]=='lambda':
                defs[bnd[0]]=bnd[1][2:]
        for cyc in cycles(defs): found.append(('letrec',cyc))
    for x in e: walk(x)
tot=0; files=[]
for root,_,fs in os.walk(sys.argv[1]):
    for f in fs:
        if not f.endswith('.scm'): continue
        p=os.path.join(root,f); tot+=1
        try: forms=parse(tokenize(open(p,errors='replace').read()))
        except Exception as ex: print('ERR',p,ex); continue
        found.clear()
        for fm in forms: walk(fm)
        if found: files.append((p,list(found)))
print('files',tot,'with internal tail cycles',len(files))
for p,fd in sorted(files): print(p, fd)
