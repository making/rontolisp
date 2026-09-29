"""Rewrites the definition.addMethod(access, x.name(), x.desc(), x.maxStack(), x.maxLocals(), x.code(), List.of())
calls javac reports (maxStack() not found) into x.code().addTo(definition, access, x.name(), x.desc())."""
import os
import re
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mig import call_span, split_args

A = os.environ.get("WORK", "/tmp/a85-work")  # where jc.sh leaves jc.log
log = open(A + "/jc.log").read().split("\n")
targets = {}
for i, l in enumerate(log):
    m = re.match(r"(/\S+\.java):(\d+): error: cannot find symbol", l)
    if m and i + 3 < len(log) and "method maxStack()" in log[i + 3] or (m and "method maxLocals()" in log[i + 3]):
        targets.setdefault(m.group(1), set()).add(int(m.group(2)))
for path, lines in targets.items():
    text = open(path).read()
    starts = [0]
    for ln in text.split("\n"):
        starts.append(starts[-1] + len(ln) + 1)
    spans = []
    for m in re.finditer(r"definition\.addMethod\(", text):
        end = call_span(text, m.end() - 1)
        first = text.count("\n", 0, m.start()) + 1
        last = text.count("\n", 0, end) + 1
        if any(first <= l <= last for l in lines):
            spans.append((m.start(), end))
    for s, e in reversed(spans):
        args = split_args(text[s + len("definition.addMethod("):e - 1])
        if len(args) != 7 or not args[3].endswith(".maxStack()") or not (args[6] == "List.of()" or re.fullmatch(r"exceptionTable\(\w+\.exceptionTable\(\)\)", args[6])):
            print("SKIP", text[s:e][:120])
            continue
        v = args[3][:-len(".maxStack()")]
        rep = "%s.code().addTo(definition, %s, %s, %s)" % (v, args[0], args[1], args[2])
        text = text[:s] + rep + text[e:]
    open(path, "w").write(text)
    print(path, len(spans))
