import subprocess, json, threading
from concurrent.futures import ThreadPoolExecutor
JAR="/nvme1n1-disk/rontolisp/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
names=[l.strip() for l in open("oracle-core.txt") if l.strip()]

# g26 dispositions of the deprecated/internal six (see .todo/g26 and
# doc/en/clojure/deviations.md): the two lowered names must resolve in both
# positions; the two documented refusals must stay unknown in both; the two
# exempt names are dropped from the gap below, with their reasons.
LOWERED={"EMPTY-NODE","StackTraceElement->vec"}
DOCUMENTED_REFUSAL={"add-classpath","Inst"}
EXEMPT={"PrintWriter-on":"builds a host PrintWriter over a proxied writer; print into a string instead",
        "primitives-classnames":"print-dup helper map for primitive Class objects, which print by name here"}

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
failures=[]
for n,v,h in res:
    if n in LOWERED and not (v and h):
        failures.append(f"lowered {n} is not resolved in both positions: [{v},{h}]")
    if n in DOCUMENTED_REFUSAL and (v or h):
        failures.append(f"documented refusal {n} unexpectedly resolves: [{v},{h}]")
neither=sorted(n for n,v,h in res if not v and not h and n not in EXEMPT)
vh=sorted(n for n,v,h in res if v and not h)
hv=sorted(n for n,v,h in res if h and not v)
counted=[n for n in names if n not in EXEMPT]
ok=sum(1 for n,v,h in res if (v or h) and n not in EXEMPT)
print("value-ok",sum(1 for _,v,h in res if v),"head-ok",sum(1 for _,v,h in res if h))
print(f"counted ok: {ok} of {len(counted)} ({len(names)-len(counted)} exempt)")
print("NEITHER (counted):",len(neither)); print(" ".join(neither))
print("head-only:",len(hv)); print(" ".join(hv))
print("value-not-head:",len(vh)); print(" ".join(vh))
print("documented refusals (must stay gaps):"," ".join(sorted(DOCUMENTED_REFUSAL)))
print("exempt (not counted):"," ".join(f"{n} ({r})" for n,r in sorted(EXEMPT.items())))
if failures:
    print("DISPOSITION FAILURES:"); print("\n".join(failures))
    raise SystemExit(1)
print("g26 dispositions hold")
