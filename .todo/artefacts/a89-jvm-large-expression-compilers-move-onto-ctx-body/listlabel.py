"""Turns lists of branch positions into labels, by name: `python3 listlabel.py FILE NAME...`.

`List<Integer> NAME = new ArrayList<>()` and a `List<Integer> NAME` parameter become
`MethodCode.Label NAME`, and `NAME.add(ctx.code.size()); ctx.emit(Opcode.X); ctx.emitU2(0);`
becomes `ctx.body.x(NAME);`, and a loop patching every position to `ctx.code.size()`
becomes `ctx.body.labelBinding(NAME)`. Other patch loops are left for the hand.
"""
import re
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from ctxmig import BRANCHES  # noqa: E402

path = sys.argv[1]
text = open(path, encoding="utf-8").read()
for name in sys.argv[2:]:
    text = re.sub(r"(?m)^(\s*)(?:final )?List<Integer> %s = new ArrayList<>\(\);$" % name,
                  r"\1MethodCode.Label %s = ctx.body.newLabel();" % name, text)
    text = re.sub(r"List<Integer> %s\b(?= *[,)])" % name, "MethodCode.Label %s" % name, text)
    text = re.sub(r"(?m)^(\s*)%s\.add\(((?:this\.)?ctx)\.code\.size\(\)\);\n\s*(?:this\.)?ctx\.emit\(Opcode\.(\w+)\);"
                  r"\n\s*(?:this\.)?ctx\.emitU2\(0\);$" % name,
                  lambda m: "%s%s.body.%s(%s);" % (m.group(1), m.group(2), BRANCHES[m.group(3)], name), text)
    # a loop patching every position to the current one
    text = re.sub(r"(?m)^(\s*)for \(int (\w+) : %s\) \{\n\s*(?:JvmEmitHelper\.)?patchBranch\(((?:this\.)?ctx), \2, "
                  r"(?:this\.)?ctx\.code\.size\(\)\);\n\s*\}$" % name,
                  lambda m: "%s%s.body.labelBinding(%s);" % (m.group(1), m.group(3), name), text)
open(path, "w", encoding="utf-8").write(text)
