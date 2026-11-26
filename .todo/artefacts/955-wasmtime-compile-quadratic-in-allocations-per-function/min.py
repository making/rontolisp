"""Minimal rontolisp-free module: one function doing N x (struct.new $s ...; drop).
usage: min.py kind N out.wat   kind: empty | i32 | eqref"""
import sys

kind, n, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
field = {"empty": "", "i32": " (field i32)", "eqref": " (field eqref)"}[kind]
arg = {"empty": "", "i32": "i32.const 0 ", "eqref": "ref.null eq "}[kind]
with open(out, "w") as f:
    f.write("(module\n  (type $s (struct%s))\n  (func (export \"f\")\n" % field)
    for _ in range(n):
        f.write("    %sstruct.new $s drop\n" % arg)
    f.write("  )\n)\n")
