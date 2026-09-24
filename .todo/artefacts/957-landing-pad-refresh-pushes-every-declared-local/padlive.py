"""Backward local liveness over one function's `wasm-tools print` text, to size how many of
the locals each landing-pad refresh (a run of >= 8 local.set, WasmLandingPad.refreshLocals)
restores are live after it. Exceptional edges: every call/throw inside a try_table may jump to
each of its catch labels (and those of enclosing try_tables).
usage: padlive.py func.wat"""
import re
import sys

lines = [l for l in open(sys.argv[1]).read().splitlines()[1:]]
ops = []
for l in lines:
    s = l.strip()
    if not s or s.startswith("(local") or s == ")":
        continue
    ops.append(s)
n = len(ops)
LABEL = re.compile(r";; label = @(\d+)")
REF = re.compile(r"\(;@(\d+);\)")

label_start = {}  # label -> (kind, index)
label_end = {}
stack = []
else_of = {}
for i, s in enumerate(ops):
    op = s.split()[0]
    if op in ("block", "loop", "if", "try_table"):
        m = LABEL.search(s)
        lab = int(m.group(1)) if m else None
        stack.append((op, i, lab))
        label_start[lab] = (op, i)
    elif op == "else":
        else_of[stack[-1][1]] = i
    elif op == "end" and stack:
        kind, start, lab = stack.pop()
        label_end[lab] = i
        if kind == "if" and start in else_of:
            else_of[else_of[start]] = i  # else -> its end


def target(lab):
    kind, start = label_start[lab]
    return start + 1 if kind == "loop" else label_end[lab] + 1


# the enclosing try_table catch targets per instruction
succ = [[] for _ in range(n)]
tstack = []  # list of lists of catch labels
ctrl = []
for i, s in enumerate(ops):
    parts = s.split()
    op = parts[0]
    nxt = [i + 1] if i + 1 < n else []
    if op in ("block", "loop", "try_table"):
        succ[i] = nxt
        ctrl.append(op)
        if op == "try_table":
            head = s.split(";;")[0]
            tstack.append([int(x) for x in REF.findall(head)])
    elif op == "if":
        ctrl.append(op)
        el = else_of.get(i)
        lab = int(LABEL.search(s).group(1))
        succ[i] = nxt + [el + 1 if el is not None else label_end[lab]]
    elif op == "else":
        succ[i] = [else_of[i]]
    elif op == "end":
        if ctrl:
            k = ctrl.pop()
            if k == "try_table":
                tstack.pop()
        succ[i] = nxt
    elif op in ("br",):
        succ[i] = [target(int(REF.search(s).group(1)))]
    elif op in ("br_if", "br_on_null", "br_on_non_null", "br_on_cast", "br_on_cast_fail"):
        succ[i] = nxt + [target(int(REF.search(s).group(1)))]
    elif op == "br_table":
        succ[i] = [target(int(x)) for x in REF.findall(s)]
    elif op in ("return", "unreachable", "return_call", "return_call_ref", "return_call_indirect"):
        succ[i] = []
    elif op in ("throw", "throw_ref"):
        succ[i] = []
    else:
        succ[i] = nxt
    if op.startswith("call") or op.startswith("return_call") or op.startswith("throw"):
        for cl in tstack:
            succ[i] += [target(l) for l in cl]

gen = [0] * n
kill = [0] * n
for i, s in enumerate(ops):
    parts = s.split()
    if parts[0] == "local.get":
        gen[i] = 1 << int(parts[1])
    elif parts[0] in ("local.set", "local.tee"):
        kill[i] = 1 << int(parts[1])

live_in = [0] * n
changed = True
rounds = 0
while changed:
    changed = False
    rounds += 1
    for i in range(n - 1, -1, -1):
        out = 0
        for j in succ[i]:
            if j < n:
                out |= live_in[j]
        v = gen[i] | (out & ~kill[i])
        if v != live_in[i]:
            live_in[i] = v
            changed = True

refreshed = kept = runs = 0
i = 0
while i < n:
    if ops[i].startswith("local.set"):
        j = i
        while j < n and ops[j].startswith("local.set"):
            j += 1
        if j - i >= 8:
            runs += 1
            after = live_in[j] if j < n else 0
            for k in range(i, j):
                refreshed += 1
                if after >> int(ops[k].split()[1]) & 1:
                    kept += 1
        i = j
        continue
    i += 1
print("instrs", n, "rounds", rounds, "refresh runs", runs, "restored", refreshed, "live after", kept)
