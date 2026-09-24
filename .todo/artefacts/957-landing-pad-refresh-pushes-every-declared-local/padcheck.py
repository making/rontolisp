"""Independent invariant check over a whole module's `wasm-tools print` text.

For every try_table catch clause the landing pad starts at the catch target. A local live
on entry to the pad (read on some path before it is written) reaches the pad through the
catch block -- what the landing-pad refresh exists to prevent. Reports, per function, the
pads whose entry has live locals, with the number of calls in the try_table body (a
single call is the exempt compiler-internal shape).
usage: padcheck.py module.wat"""
import re
import sys

from wat_cfg import cfg, liveness, ops_of


def functions(path):
    cur = None
    for line in open(path):
        if line.startswith("  (func "):
            if cur is not None:
                yield cur
            cur = [line]
        elif cur is not None:
            if line.startswith("  (") and not line.startswith("  (func "):
                yield cur
                cur = None
            else:
                cur.append(line)
    if cur is not None:
        yield cur


TYPE = re.compile(r"\(ref null \d+\)|\(ref \d+\)|[a-z0-9]+ref|i32|i64|f32|f64|v128")


def reference_locals(lines):
    """Bitset of the parameter and local indices whose type is a reference."""
    header = lines[0]
    params = re.search(r"\(param ([^)]*(?:\([^)]*\)[^)]*)*)\)", header)
    types = TYPE.findall(params.group(1)) if params else []
    decl = lines[1].strip() if len(lines) > 1 else ""
    if decl.startswith("(local"):
        types += TYPE.findall(decl[len("(local"):])
    mask = 0
    for k, t in enumerate(types):
        if "ref" in t:
            mask |= 1 << k
    return mask


total_pads = 0
multi = 0
single = 0
for lines in functions(sys.argv[1]):
    m = re.match(r"  \(func \(;(\d+);\)", lines[0])
    fidx = int(m.group(1)) if m else -1
    ops = ops_of(lines)
    succ, pads, calls_in_try = cfg(ops)
    if not pads:
        continue
    live_in = liveness(ops, succ)
    refs = reference_locals(lines)
    total_pads += len(pads)
    for entry, t in pads:
        live = (live_in[entry] if entry < len(ops) else 0) & refs
        if not live:
            continue
        locs = [k for k in range(live.bit_length()) if live >> k & 1]
        if calls_in_try[t] > 1:
            multi += 1
            print("func", fidx, "pad@", entry, "try@", t, "calls in try", calls_in_try[t], "live on entry",
                  len(locs), locs[:8])
        else:
            single += 1
print("pads", total_pads, "live-on-entry pads: multi-call", multi, "single-call", single)
