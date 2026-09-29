import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mig import fix_imports
for p in sys.argv[1:]:
    s = open(p).read()
    n = fix_imports(s)
    if n != s:
        open(p, "w").write(n)
        print("imports fixed", p)
