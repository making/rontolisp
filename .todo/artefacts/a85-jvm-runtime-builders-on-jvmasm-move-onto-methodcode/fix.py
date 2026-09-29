"""Applies the mechanical fixes javac's errors name (jc.log): a wrapper where an entry is
wanted gets `.entry()`."""
import os
import re
import sys
from collections import defaultdict

A = os.environ.get("WORK", "/tmp/a85-work")  # where jc.sh leaves jc.log
log = open(A + "/jc.log").read().split("\n")
WRAP = r"(ClassConstant|MethodrefConstant|FieldrefConstant|StringConstant|IntegerConstant|LongConstant|DoubleConstant)"
ENTRY = r"(ClassEntry|MethodRefEntry|FieldRefEntry|StringEntry|IntegerEntry|LongEntry|DoubleEntry|MemberRefEntry|LoadableConstantEntry|InterfaceMethodRefEntry|PoolEntry)"
edits = defaultdict(list)  # file -> [(line, col, kind)]
i = 0
while i < len(log):
    m = re.match(r"(/\S+\.java):(\d+): error: (.*)", log[i])
    if m:
        path, line, msg = m.group(1), int(m.group(2)), m.group(3)
        caret = log[i + 2] if i + 2 < len(log) else ""
        col = caret.index("^") if "^" in caret else -1
        src = log[i + 1]
        # javac prints tabs as-is, so the caret column counts characters of the source line
        if re.search(r"incompatible types: %s cannot be converted to %s" % (WRAP, ENTRY), msg):
            edits[path].append((line, col, "entry"))
        elif "bad type in conditional expression" in msg and i + 3 < len(log) and re.search(
                r"%s cannot be converted to %s" % (WRAP, ENTRY), log[i + 3]):
            edits[path].append((line, col, "entry"))
        elif "bad type in conditional expression" in msg and i + 3 < len(log) and \
                "MemberRefEntry cannot be converted to MethodRefEntry" in log[i + 3]:
            edits[path].append((line, col, "methodref"))
        elif "incompatible types: MemberRefEntry cannot be converted to InterfaceMethodRefEntry" in msg:
            edits[path].append((line, col, "imethodref"))
        elif "incompatible types: MemberRefEntry cannot be converted to MethodRefEntry" in msg:
            edits[path].append((line, col, "methodref"))
        elif "inference variable T has incompatible bounds" in msg:
            ctx = "\n".join(log[i + 3:i + 6])
            if re.search(r"lower bounds: %s" % WRAP, ctx) and re.search(ENTRY, ctx):
                edits[path].append((line, -1, "requireNonNull"))
    i += 1


def expr_end(s, start):
    i = start
    n = len(s)
    while i < n:
        c = s[i]
        if c.isalnum() or c in "_." or (c in " \t\n" and i + 1 < n and re.match(r"\s*\.", s[i:]) ):
            i += 1
        elif c in "([":
            depth = 0
            while i < n:
                if s[i] == '"':
                    i += 1
                    while i < n and s[i] != '"':
                        i += 2 if s[i] == chr(92) else 1
                    i += 1
                    continue
                if s[i] in "([":
                    depth += 1
                elif s[i] in ")]":
                    depth -= 1
                    if depth == 0:
                        i += 1
                        break
                i += 1
        else:
            break
    return i


for path, es in edits.items():
    text = open(path, encoding="utf-8").read()
    starts = [0]
    for ln in text.split("\n"):
        starts.append(starts[-1] + len(ln) + 1)
    seen = set()
    for line, col, kind in sorted(es, key=lambda e: (e[0], e[1]), reverse=True):
        if (line, col, kind) in seen:
            continue
        seen.add((line, col, kind))
        base = starts[line - 1]
        if kind == "imethodref":
            at = base + col
            if text[at - 6:at] == ".entry":
                at -= 6
            end = expr_end(text, at)
            if text[at:end].endswith(".entry()"):
                text = text[:end - len(".entry()")] + ".interfaceMethodRefEntry()" + text[end:]
            else:
                print("NOFIX imethodref %s:%d %s" % (path, line, text[at:end]))
            continue
        if kind == "methodref":
            at = base + col
            if text[at - 6:at] == ".entry":
                at -= 6
            end = expr_end(text, at)
            if text[at:end].endswith(".entry()"):
                text = text[:end - len(".entry()")] + ".methodRefEntry()" + text[end:]
            else:
                print("NOFIX methodref %s:%d %s" % (path, line, text[at:end]))
            continue
        if kind == "entry":
            at = base + col
        else:
            m = re.compile(r"Objects\.requireNonNull\(").search(text, base, starts[line])
            if not m:
                print("NOFIX %s:%d" % (path, line))
                continue
            at = m.start()
        end = expr_end(text, at)
        if text[at:end].endswith(".entry()"):
            continue
        text = text[:end] + ".entry()" + text[end:]
    open(path, "w", encoding="utf-8").write(text)
    print(path.rsplit("/", 1)[-1], len(es))
