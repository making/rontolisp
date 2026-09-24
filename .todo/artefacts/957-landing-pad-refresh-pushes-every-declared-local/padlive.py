"""Per refresh run (the local.set run right after a landing block's payload set), how many of
the restored locals are live after it -- labels resolved by the control stack (wat_cfg), and
the push runs NOT counted as uses (they are what is being sized). padfix.py is the same count
at the least fixed point, where a kept push does count. The first version of this tool keyed
labels by wasm-tools' `@N` annotation, which is a nesting DEPTH, and reported 156 for what is
80. usage: padlive.py func.wat"""
import sys

from wat_cfg import cfg, liveness, ops_of

ops = ops_of(open(sys.argv[1]).read().splitlines())
succ, pads, _ = cfg(ops)
n = len(ops)


def run_at(i, op):
    j = i
    while j < n and ops[j].split()[0] == op:
        j += 1
    return j


# push runs: the local.get run right before a landing block (a catch target's opener)
push_uses = set()
refresh = []
for entry, t in pads:
    # entry = the instruction after the landing block's end: local.set payload, then pops
    if entry >= n or ops[entry].split()[0] != "local.set":
        continue
    j = run_at(entry + 1, "local.set")
    refresh.append((entry + 1, j))
# the push runs: gets immediately before each landing block opener
stack = []
match = {}
for i, s in enumerate(ops):
    op = s.split()[0]
    if op in ("block", "loop", "if", "try_table"):
        stack.append(i)
    elif op == "end" and stack:
        o = stack.pop()
        match[i] = o
for entry, t in pads:
    o = match.get(entry - 1)
    if o is None:
        continue
    k = o - 1
    while k >= 0 and ops[k].split()[0] == "local.get":
        push_uses.add(k)
        k -= 1
live_in = liveness(ops, succ, push_uses)
restored = kept = 0
for a, b in sorted(set(refresh)):
    after = live_in[b] if b < n else 0
    for k in range(a, b):
        restored += 1
        if after >> int(ops[k].split()[1]) & 1:
            kept += 1
print("instrs", n, "refresh runs", len(set(refresh)), "restored", restored,
      "live after (push reads not counted)", kept)
