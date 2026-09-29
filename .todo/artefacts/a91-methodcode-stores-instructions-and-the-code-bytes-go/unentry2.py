"""unentry2.py JC_LOG: once pool2.py has made the wrappers entries, removes each `.entry()`,
`.methodRefEntry()` or `.interfaceMethodRefEntry()` javac reports as a missing method (a86's
unentry.py, reading any log: a87's mkjar.sh writes $WORK/jc.log). Prints the count per file and
every report it could not place."""
import re
import sys
from collections import defaultdict

log = open(sys.argv[1]).read().split("\n")
edits = defaultdict(list)
for i, line in enumerate(log):
    m = re.match(r"(/\S+\.java):(\d+): error: cannot find symbol", line)
    if not m or i + 3 >= len(log):
        continue
    mm = re.search(r"symbol:\s+method (entry|methodRefEntry|interfaceMethodRefEntry)\(\)", log[i + 3])
    if mm:
        edits[m.group(1)].append((int(m.group(2)), log[i + 2].index("^"), mm.group(1)))
for path, es in edits.items():
    lines = open(path, encoding="utf-8").read().split("\n")
    # right to left within a line, so an earlier column still points where javac said
    for line, col, name in sorted(set(es), key=lambda e: (e[0], -e[1])):
        text = lines[line - 1]
        token = "." + name + "()"
        at = text.find(token, max(0, col - 1))
        if at < 0:
            print("NOFIX", path, line, text.strip())
            continue
        lines[line - 1] = text[:at] + text[at + len(token):]
    open(path, "w", encoding="utf-8").write("\n".join(lines))
    print(path.rsplit("/", 1)[-1], len(es))
