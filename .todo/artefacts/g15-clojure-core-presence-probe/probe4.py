import subprocess, json, threading
from concurrent.futures import ThreadPoolExecutor
JAR="/nvme1n1-disk/rontolisp/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
names=[l.strip() for l in open("oracle-core.txt") if l.strip()]
tl=threading.local()
def run(src):
    f=getattr(tl,"f",None)
    if f is None:
        import uuid; f="_p-"+uuid.uuid4().hex[:8]+".clj"; tl.f=f
    open(f,"w").write(src)
    try:
        r=subprocess.run(["java","-jar",JAR,f],capture_output=True,text=True,timeout=30)
        return r.stderr+r.stdout
    except subprocess.TimeoutExpired as e:
        return (e.stderr or b"").decode()+(e.stdout or b"").decode()
def probe(n):
    v="unknown name" not in run(f"(def v {{{n} {n}}})\n")
    h="unknown name" not in run(f"({n})\n")
    return n,v,h
with ThreadPoolExecutor(12) as ex:
    res=list(ex.map(probe,names))
json.dump({n:[v,h] for n,v,h in res},open("core-probe.json","w"))
neither=sorted(n for n,v,h in res if not v and not h)
vh=sorted(n for n,v,h in res if v and not h)
hv=sorted(n for n,v,h in res if h and not v)
print("value-ok",sum(1 for _,v,h in res if v),"head-ok",sum(1 for _,v,h in res if h))
print("NEITHER:",len(neither)); print(" ".join(neither))
print("head-only:",len(hv)); print(" ".join(hv))
print("value-not-head:",len(vh)); print(" ".join(vh))
