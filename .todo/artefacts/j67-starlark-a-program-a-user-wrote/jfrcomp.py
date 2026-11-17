import sys, re, subprocess
# usage: jfrcomp.py file.jfr [min_ms]: the compilations of a recording, start, duration, level, osr, method, size
out = subprocess.run(['jfr','print','--events','jdk.Compilation,jdk.Deoptimization',sys.argv[1]],capture_output=True,text=True).stdout
mn = float(sys.argv[2]) if len(sys.argv)>2 else 20
rows=[]
for ev in re.split(r'\n(?=jdk\.)', out):
    g=lambda k: (re.search(r'^\s+%s = (.*)$'%k, ev, re.M) or [None,''])[1]
    if ev.startswith('jdk.Compilation'):
        d=g('duration'); ms=float(d.replace(' ms','').replace(',','')) if d.endswith('ms') else (float(d.replace(' s','').replace(',',''))*1000 if d.endswith(' s') else 0)
        if ms>=mn: rows.append((g('startTime'),'compile %7.1f ms L%s osr=%s %s codeSize=%s inlinedBytes=%s'%(ms,g('compileLevel'),g('isOsr'),g('method'),g('codeSize'),g('inlinedBytes'))))
    elif ev.startswith('jdk.Deoptimization'):
        rows.append((g('startTime'),'DEOPT %s reason=%s action=%s bci=%s instr=%s'%(g('method'),g('reason'),g('action'),g('bci'),g('instruction'))))
for t,r in sorted(rows): print(t,r)
