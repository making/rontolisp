"""records.py FILE RECORD: drops the maxStack/maxLocals of every five-argument construction."""
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mig import rewrite_calls

p, rec = sys.argv[1], sys.argv[2]
s = open(p).read()


def fn(m, args):
    if len(args) == 5:
        return "new %s(%s, %s, %s)" % (rec, args[0], args[1], args[4])
    return None


n = rewrite_calls(s, r"new (%s)\(" % rec, fn)
if n != s:
    open(p, 'w').write(n)
    print("fixed", p)
