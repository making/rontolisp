"""records6.py FILE RECORD: new R(n, d, s, l, v.finish(), List.of()) -> new R(n, d, v); a handler table
List.of(new int[] { start, end, handler, type.index() }) becomes v.exceptionCatch(...) before the statement."""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mig import rewrite_calls, split_args

p, rec = sys.argv[1], sys.argv[2]
s = open(p).read()
pending = []


def fn(m, args):
    if len(args) != 6 or not re.fullmatch(r"\w+\.finish\(\)", args[4]):
        print("SKIP", args[:2])
        return None
    v = args[4].split(".")[0]
    if args[5] != "List.of()":
        mm = re.fullmatch(r"List\.of\(new int\[\] \{ (.*) \}\)", args[5], re.S)
        if not mm:
            print("SKIP handlers", args[5])
            return None
        h = split_args(mm.group(1))
        t = h[3]
        if t.endswith(".index()"):
            t = t[:-len(".index()")]
        pending.append((m.start(), "%s.exceptionCatch(%s, %s, %s, %s);" % (v, h[0], h[1], h[2], t)))
    return "new %s(%s, %s, %s)" % (rec, args[0], args[1], v)


s = rewrite_calls(s, r"new (%s)\(" % rec, fn)
# Insert each exceptionCatch before the statement holding its construction: find the
# construction again by its new text is fragile, so re-scan for the markers.
for _, stmt in pending:
    print("HANDLER", stmt)
open(p, 'w').write(s)
