"""handlers.py FILE RECORD 'stmt' ...: puts each exceptionCatch before the statement that
constructs the record after the handler label's binding, and turns `int x = v.pos();` into
bound labels."""
import re
import sys

p, rec = sys.argv[1], sys.argv[2]
stmts = sys.argv[3:]
s = open(p).read()
s = re.sub(r"\bint (\w+) = (\w+)\.pos\(\);", r"MethodCode.Label \1 = \2.newBoundLabel();", s)
lines = s.split("\n")
for stmt in stmts:
    m = re.match(r"(\w+)\.exceptionCatch\((\w+), (\w+), (\w+), ", stmt)
    v, handler = m.group(1), m.group(4)
    bind = [i for i, l in enumerate(lines) if l.endswith("MethodCode.Label %s = %s.newBoundLabel();" % (handler, v))]
    assert bind, stmt
    i = bind[0]
    while "new %s(" % rec not in lines[i]:
        i += 1
    j = i
    while not re.search(r"[;{}]\s*(//.*)?$", lines[j - 1]):
        j -= 1
    indent = re.match(r"\t*", lines[j]).group(0)
    lines.insert(j, indent + stmt)
    # a later binding of the same name must not match the one just done
    lines[bind[0]] = lines[bind[0]].replace("newBoundLabel();", "newBoundLabel(); ")
s = "\n".join(lines).replace("newBoundLabel(); ", "newBoundLabel();")
open(p, 'w').write(s)
