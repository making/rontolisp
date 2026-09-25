import sys, collections
d = collections.defaultdict(list)
for l in open(sys.argv[1]):
    p = l.split()
    if len(p) != 6: continue
    d[(p[0], p[1])].append((int(p[3]), int(p[4]), float(p[5])))
for n in sorted({k[0] for k in d}):
    g = d[(n,'glibc')]; gc=sorted(x[0] for x in g); gi=min(x[1] for x in g)
    row=f"{n:11s} glibc {gc[0]/1e9:.3f}-{gc[-1]/1e9:.3f} ({gi/1e9:.2f}G ins)"
    for v in ('musl','own'):
        m=d[(n,v)]; mc=sorted(x[0] for x in m); mi=min(x[1] for x in m)
        row+=f" | {v} {mc[0]/1e9:.3f}-{mc[-1]/1e9:.3f} {100*(mc[0]/gc[0]-1):+.1f}% ins {100*(mi/gi-1):+.1f}%"
    print(row)
