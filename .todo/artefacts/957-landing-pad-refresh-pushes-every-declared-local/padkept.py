"""Count what each landing pad still refreshes in an already-narrowed function: the
local.set / local.tee run right after the pad's payload store, and the local.get run in
front of the landing block. usage: padkept.py func.wat"""
import sys

from wat_cfg import cfg, ops_of

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
seen = set()
pops_total = pushes_total = pads_n = 0
for entry, t in pads:
    if entry in seen or entry >= n:
        continue
    seen.add(entry)
    pads_n += 1
    j = entry + 1
    while j < n and ops[j].split()[0] in ("local.set",):
        j += 1
    if j < n and ops[j].split()[0] == "local.tee" and j > entry + 1:
        j += 1
    pops_total += j - (entry + 1)
    o = match.get(entry - 1)
    k = o
    while k is not None and k - 1 >= 0 and ops[k - 1].split()[0] == "local.get":
        k -= 1
        pushes_total += 1
print("pads", pads_n, "refresh stores", pops_total, "gets in front of landing blocks", pushes_total)
