"""Control flow of one function's `wasm-tools print` text, with labels resolved by the
control stack. wasm-tools annotates a block with `;; label = @N` where N is its nesting
DEPTH, and a reference `(;@N;)` names the enclosing block at depth N from where it stands,
so the same N names different blocks in different places.

ops(lines) -> instruction strings; cfg(ops) -> (succ, catch_targets, calls_in_try), where
every call/throw inside a try_table gets an edge to the catch targets of every enclosing
try_table, and catch_targets lists (pad entry, try_table index)."""
import re

REF = re.compile(r"\(;@(\d+);\)")


def ops_of(lines):
    ops = []
    for l in lines[1:]:
        s = l.strip()
        if not s or s.startswith("(local") or s == ")":
            continue
        ops.append(s)
    return ops


def cfg(ops):
    n = len(ops)
    match = {}
    else_of = {}
    stack = []
    for i, s in enumerate(ops):
        op = s.split()[0]
        if op in ("block", "loop", "if", "try_table"):
            stack.append(i)
        elif op == "else":
            else_of[stack[-1]] = i
        elif op == "end" and stack:
            o = stack.pop()
            match[o] = i
            match[i] = o

    ctrl = []  # opener indices

    def target(depth_label):
        o = ctrl[depth_label - 1]
        if ops[o].split()[0] == "loop":
            return o + 1
        return match[o] + 1

    succ = [[] for _ in range(n)]
    tstack = []  # per enclosing try_table: its catch targets
    towner = []
    pads = []
    calls_in_try = {}
    for i, s in enumerate(ops):
        op = s.split()[0]
        nxt = [i + 1] if i + 1 < n else []
        head = s.split(";;")[0]
        if op in ("block", "loop"):
            succ[i] = nxt
            ctrl.append(i)
        elif op == "try_table":
            succ[i] = nxt
            targets = [target(int(x)) for x in REF.findall(head)]
            for t in targets:
                pads.append((t, i))
            ctrl.append(i)
            tstack.append(targets)
            towner.append(i)
            calls_in_try[i] = 0
        elif op == "if":
            succ[i] = nxt + [else_of[i] + 1 if i in else_of else match[i]]
            ctrl.append(i)
        elif op == "else":
            succ[i] = [match[ctrl[-1]]]
        elif op == "end":
            if ctrl:
                o = ctrl.pop()
                if ops[o].split()[0] == "try_table":
                    tstack.pop()
                    towner.pop()
            succ[i] = nxt
        elif op == "br":
            succ[i] = [target(int(REF.search(head).group(1)))]
        elif op in ("br_if", "br_on_null", "br_on_non_null", "br_on_cast", "br_on_cast_fail"):
            succ[i] = nxt + [target(int(REF.search(head).group(1)))]
        elif op == "br_table":
            succ[i] = [target(int(x)) for x in REF.findall(head)]
        elif op in ("return", "unreachable", "return_call", "return_call_ref", "return_call_indirect",
                    "throw", "throw_ref"):
            succ[i] = []
        else:
            succ[i] = nxt
        if op.startswith("call") or op.startswith("return_call") or op.startswith("throw"):
            for targets in tstack:
                succ[i] += targets
            for t in towner:
                calls_in_try[t] += 1
    return succ, pads, calls_in_try


def liveness(ops, succ, skip_uses=None):
    """Backward liveness; returns live_in per instruction as an int bitset. Instructions
    whose index is in skip_uses are not counted as uses."""
    n = len(ops)
    gen = [0] * n
    kill = [0] * n
    for i, s in enumerate(ops):
        parts = s.split()
        if parts[0] == "local.get" and (skip_uses is None or i not in skip_uses):
            gen[i] = 1 << int(parts[1])
        elif parts[0] in ("local.set", "local.tee"):
            kill[i] = 1 << int(parts[1])
    live_in = [0] * n
    changed = True
    while changed:
        changed = False
        for i in range(n - 1, -1, -1):
            out = 0
            for j in succ[i]:
                if j < n:
                    out |= live_in[j]
            v = gen[i] | (out & ~kill[i])
            if v != live_in[i]:
                live_in[i] = v
                changed = True
    return live_in
