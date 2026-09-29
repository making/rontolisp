"""Pool operands held as an int index into their constants, so ctxmig.py maps their uses:
`python3 intidx.py FILE TYPE NAME...`.

`int NAME = EXPR.index();` becomes `TYPE NAME = EXPR;` and `ctx.emitU2(NAME)` becomes
`ctx.emitU2(NAME.index())`, the shape ctxmig.py reads. Run ctxmig.py after it.
"""
import re
import sys

path, typ = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()
for name in sys.argv[3:]:
    text, n = re.subn(r"(?m)^(\s*)(final )?int %s = (.*)\.index\(\);$" % name,
                      lambda m: "%s%s%s %s = %s;" % (m.group(1), m.group(2) or "", typ, name, m.group(3)), text)
    if n != 1:
        print("%s: %d declarations of %s" % (path, n, name))
    text = re.sub(r"emitU2\(%s\)" % name, "emitU2(%s.index())" % name, text)
open(path, "w", encoding="utf-8").write(text)
