import re,sys
# every top-level (DEFUN name ...) of an IR dump, the functions its body names, and which can reach a
# point where a thread is suspended: a wait, a sleep, a yield -- and, in the second count, a monitor's entry
def forms(text):
    return [line for line in text.split('\n') if line.startswith('(')]
tok=re.compile(r'\|[^|]*\||[^\s()\'#`,"]+')
def run(path):
    text=open(path).read()
    defs={}
    for f in forms(text):
        m=re.match(r'\((?:CL:)?DEFUN\s+(\|[^|]*\||\S+)',f)
        if m: defs[m.group(1).upper() if not m.group(1).startswith('|') else m.group(1)]=f
    names=set(defs)
    calls={n:{t for t in tok.findall(b) if t in names and t!=n} for n,b in defs.items()}
    java=[n for n in names if n.startswith('|')]
    def closure(seeds):
        can=set(s for s in seeds if s in names)
        changed=True
        while changed:
            changed=False
            for n,c in calls.items():
                if n not in can and c & can:
                    can.add(n);changed=True
        return can
    waits=[n for n in names if re.search(r'%JAVA-(THREAD-WAIT|PARK|THREAD-SLEEP|THREAD-YIELD|MONITOR-WAIT)$',n)]
    enter=waits+[n for n in names if n.endswith('%JAVA-MONITOR-ENTER')]
    a=closure(waits); b=closure(enter)
    print(path, 'functions', len(names), 'of the program and library', len(java), '| reach a wait/sleep/yield:', len([n for n in a if n in java]), '| or a monitor entry:', len([n for n in b if n in java]))
for p in sys.argv[1:]: run(p)
