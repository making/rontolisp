"""Extracts Lisp programs from the Java tests (text blocks and one-line string literals that
look like Lisp) into one file each, plus the ci-spec corpus as one program."""
import glob
import os
import re
import sys

W = os.environ.get("ROOT", os.getcwd())  # the repository
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)


def unescape(s):
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            out.append({"n": "\n", "t": "\t", "\\": "\\", '"': '"', "'": "'", "s": " ", "\n": ""}.get(n, "\\" + n))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def text_blocks(src):
    for m in re.finditer(r'"""[ \t]*\n(.*?)"""', src, re.S):
        body = m.group(1)
        lines = body.split("\n")
        # the closing delimiter's own line counts toward the indentation
        nonblank = [l for l in lines if l.strip()] + ([lines[-1]] if lines and not lines[-1].strip() else [])
        indent = min((len(l) - len(l.lstrip()) for l in nonblank), default=0)
        yield unescape("\n".join(l[indent:] for l in lines))


count = 0
files = sorted(glob.glob(W + "/src/test/java/**/*.java", recursive=True))
seen = set()
for f in files:
    if "/wasm/" in f or "Wasm" in os.path.basename(f):
        continue
    src = open(f, encoding="utf-8").read()
    progs = list(text_blocks(src))
    for m in re.finditer(r'(?:compileAndRun\w*|compile\w*|run\w*)\(\s*"((?:[^"\\\n]|\\.)*)"', src):
        progs.append(unescape(m.group(1)))
    for p in progs:
        if not p.lstrip().startswith("(") or p in seen:
            continue
        seen.add(p)
        count += 1
        name = "%s-%04d.lisp" % (os.path.basename(f)[:-5], count)
        open(os.path.join(OUT, name), "w", encoding="utf-8").write(p)
print(count, "programs")
