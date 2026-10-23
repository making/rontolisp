import subprocess, json, threading, uuid, os
from concurrent.futures import ThreadPoolExecutor
# The executable jar of the checkout holding this directory (override with RONTOLISP_JAR).
JAR=os.environ.get("RONTOLISP_JAR",os.path.join(os.path.dirname(os.path.abspath(__file__)),"..","..","..","target","rontolisp-0.1.0-SNAPSHOT-exec.jar"))
pairs=[l.strip() for l in open("others-q.txt") if l.strip()]
nses=sorted({p.split("/")[0] for p in pairs})
tl=threading.local()
def run(src):
    f=getattr(tl,"f",None)
    if f is None: f="_q-"+uuid.uuid4().hex[:8]+".clj"; tl.f=f
    open(f,"w").write("(require %s)\n"%(" ".join("'%s"%n for n in nses))+src)
    try:
        r=subprocess.run(["java","-jar",JAR,f],capture_output=True,text=True,timeout=30)
        return r.stderr+r.stdout
    except subprocess.TimeoutExpired as e:
        return "TIMEOUT"
def probe(q):
    ns,name=q.split("/",1)
    v="unknown name" not in run(f"(def v {{{q} {ns}/{name}}})\n") and "Could not" not in run("")
    h="unknown name" not in run(f"({q})\n")
    return q,v,h
with ThreadPoolExecutor(12) as ex:
    res=list(ex.map(probe,pairs))
json.dump({q:[v,h] for q,v,h in res},open("others-probe.json","w"))
from collections import defaultdict
miss=defaultdict(list)
for q,v,h in res:
    if not v and not h: miss[q.split("/")[0]].append(q.split("/")[1])
for ns in sorted(miss): print(ns,len(miss[ns]),":"," ".join(sorted(miss[ns])))
print("total ok:",sum(1 for _,v,h in res if v or h),"of",len(res))
