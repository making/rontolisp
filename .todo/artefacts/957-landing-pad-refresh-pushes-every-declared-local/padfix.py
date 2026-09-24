"""The least fixed point of the landing-pad narrowing, computed independently of the Java
pass: a push read counts as a use only while the pad keeps that local, and a local is kept
when it is live after the pad's refresh run. Pads are the catch targets whose first
instruction is the payload local.set followed by the refresh run, with the push run the
local.get run right in front of the landing block.
usage: padfix.py func.wat"""
import sys

from wat_cfg import cfg, liveness, ops_of

ops = ops_of(open(sys.argv[1]).read().splitlines())
succ, pads, _ = cfg(ops)
n = len(ops)
match = {}
stack = []
for i, s in enumerate(ops):
    op = s.split()[0]
    if op in ("block", "loop", "if", "try_table"):
        stack.append(i)
    elif op == "end" and stack:
        match[i] = stack.pop()

carries = []  # (push indices in order, pop indices in order, cont)
seen = set()
for entry, t in pads:
    if entry in seen or entry >= n or ops[entry].split()[0] != "local.set":
        continue
    seen.add(entry)
    o = match.get(entry - 1)
    if o is None:
        continue
    j = entry + 1
    while j < n and ops[j].split()[0] == "local.set":
        j += 1
    pops = list(range(entry + 1, j))
    k = o
    pushes = []
    while k - 1 >= 0 and ops[k - 1].split()[0] == "local.get" and len(pushes) < len(pops):
        k -= 1
        pushes.insert(0, k)
    if len(pushes) != len(pops):
        continue
    ok = all(ops[pushes[x]].split()[1] == ops[pops[len(pops) - 1 - x]].split()[1] for x in range(len(pops)))
    if ok and pops:
        carries.append((pushes, pops, j))

all_push = set(i for c in carries for i in c[0])
kept_push = set()
rounds = 0
while True:
    rounds += 1
    live_in = liveness(ops, succ, all_push - kept_push)
    grew = False
    for pushes, pops, cont in carries:
        after = live_in[cont] if cont < n else 0
        for x, p in enumerate(pushes):
            local = int(ops[p].split()[1])
            if after >> local & 1 and p not in kept_push:
                kept_push.add(p)
                grew = True
    if not grew:
        break
total = sum(len(c[0]) for c in carries)
print("carries", len(carries), "carried", total, "kept (least fixed point)", len(kept_push), "rounds", rounds)
