import sys, re, subprocess, collections
# usage: jfrcallers.py file.jfr frame-substring [depth]
out = subprocess.run(['jfr','print','--events','jdk.ExecutionSample','--stack-depth','64',sys.argv[1]],capture_output=True,text=True).stdout
pat = sys.argv[2]; depth = int(sys.argv[3]) if len(sys.argv)>3 else 4
c = collections.Counter(); n=0
for ev in out.split('jdk.ExecutionSample {')[1:]:
    frames = re.findall(r'^\s+([^\s(]+)\(.*?\)', ev, re.M)
    if not frames: continue
    n+=1
    for i,f in enumerate(frames):
        if pat in f:
            c[' <- '.join(x.replace('RONTOLISP$colon$colon$pct','%').replace('Prog.','') for x in frames[max(0,i-depth+1):i+depth])]+=1
            break
for k,v in c.most_common(25): print('%5.1f%% %s'%(100*v/n,k))
