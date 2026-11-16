import sys, re, subprocess, collections
# usage: jfrtop.py file.jfr [depth]
out = subprocess.run(['jfr','print','--events','jdk.ExecutionSample','--stack-depth','64',sys.argv[1]],capture_output=True,text=True).stdout
self_c = collections.Counter(); incl = collections.Counter(); n=0
for ev in out.split('jdk.ExecutionSample {')[1:]:
    frames = re.findall(r'^\s+([^\s(]+)\(.*?\)', ev, re.M)
    if not frames: continue
    n+=1
    self_c[frames[0]]+=1
    for f in set(frames): incl[f]+=1
print('samples',n)
print('-- self')
for f,c in self_c.most_common(30): print('%5.1f%% %s'%(100*c/n,f))
print('-- inclusive')
for f,c in incl.most_common(45): print('%5.1f%% %s'%(100*c/n,f))
