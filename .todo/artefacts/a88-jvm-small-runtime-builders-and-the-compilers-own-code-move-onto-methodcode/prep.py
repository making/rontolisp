"""Normalizes qualified raw helpers so raw.py sees its idiom."""
import re
import sys

for path in sys.argv[1:]:
    t = open(path, encoding="utf-8").read()
    t = t.replace("JvmRuntimeBuilder.emitU2(", "emitU2(")
    t = t.replace("JvmRuntimeBuilder.patchBranch(", "patchBranch(")
    t = t.replace("new java.util.ArrayList<>()", "new ArrayList<>()")
    t = re.sub(r"java\.util\.List<Integer>", "List<Integer>", t)
    open(path, "w", encoding="utf-8").write(t)
