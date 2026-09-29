"""rawx.py FILE...: a86's raw.py taught the idioms of a raw list written through helpers of its
own (the numeric and complex runtimes), rewritten into raw.py's idiom before it runs:

- the shared raw helpers called qualified (`JvmRuntimeBuilder.emitU2(c, ...)`, `emitLdc`,
  `patchBranch`, `emitIntConstStatic`) as the bare names raw.py knows;
- a local branch helper (`int p = branch(c, Opcode.X);`, `p = jump(c, Opcode.X);`,
  `list.add(jump(c, Opcode.X));`) as the position read + branch + placeholder raw.py turns into
  a label;
- `patch(c, p);` (bind here) as `patchBranch(c, p, c.size());`;
- the local/constant/call helpers (`aload(c, s)` and the other loads and stores,
  `emitInt(c, v)`, `call(c, ref)`) and `invoke(c, Opcode.INVOKEX, ref)` as the typed calls;
- a join `int NAME = c.size();` followed only by `patchBranch(c, X, NAME);` lines, NAME used
  nowhere else in the method (comments aside), as the patches to `c.size()`.

Prints raw.py's report plus every line still in a raw idiom. Left for a hand: what raw.py leaves
(handler bounds, `int p = -1` sentinels, an opcode passed as a value), a helper answering
positions (`int[]`, a list it fills: make it take the label), and a join NAME that also names
something else in its method."""
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                                "a86-jvm-io-and-socket-runtime-code-lists-move-onto-methodcode"))
from raw import convert  # noqa: E402

BRANCHES = {"IFEQ", "IFNE", "IFLT", "IFGE", "IFGT", "IFLE", "IF_ICMPEQ", "IF_ICMPNE", "IF_ICMPLT", "IF_ICMPGE",
            "IF_ICMPGT", "IF_ICMPLE", "IF_ACMPEQ", "IF_ACMPNE", "IFNULL", "IFNONNULL", "GOTO"}
LOCALS = ("aload", "astore", "iload", "istore", "lload", "lstore", "dload", "dstore", "fload", "fstore")


def pre(text):
    text = re.sub(r"\bJvmRuntimeBuilder\.(emitU2|emitLdc|patchBranch)\(", r"\1(", text)
    text = re.sub(r"\bJvmRuntimeBuilder\.emitIntConstStatic\(", "emitIntConst(", text)

    def br(m):
        ind, decl, var, cv, op = m.group(1), m.group(2) or "", m.group(3), m.group(4), m.group(5)
        if op not in BRANCHES:
            return m.group(0)
        return "%s%s%s = %s.size();\n%s%s.add(Opcode.%s);\n%semitU2(%s, 0);" % (ind, decl, var, cv, ind, cv, op, ind, cv)
    text = re.sub(r"(?m)^(\t*)(int )?(\w+) = (?:branch|jump)\((\w+), Opcode\.(\w+)\);$", br, text)

    def lbr(m):
        ind, lst, cv, op = m.group(1), m.group(2), m.group(3), m.group(4)
        if op not in BRANCHES:
            return m.group(0)
        return "%s%s.add(%s.size());\n%s%s.add(Opcode.%s);\n%semitU2(%s, 0);" % (ind, lst, cv, ind, cv, op, ind, cv)
    text = re.sub(r"(?m)^(\t*)(\w+)\.add\((?:branch|jump)\((\w+), Opcode\.(\w+)\)\);$", lbr, text)
    text = re.sub(r"(?m)^(\t*)patch\((\w+), (\w+)\);$", r"\1patchBranch(\2, \3, \2.size());", text)
    for name in LOCALS:
        text = re.sub(r"(?m)^(\t*)%s\((\w+), ([^;]*)\);$" % name, r"\1\2.%s(\3);" % name, text)
    text = re.sub(r"(?m)^(\t*)emitInt\((\w+), ([^;]*)\);$", r"\1\2.loadConstant(\3);", text)
    text = re.sub(r"(?m)^(\t*)call\((\w+), ([^;]*)\);$", r"\1\2.invokestatic(\3);", text)
    text = re.sub(r"(?m)^(\t*)invoke\((\w+), Opcode\.INVOKE(STATIC|VIRTUAL|SPECIAL|INTERFACE), ([^;]*)\);$",
                  lambda m: "%s%s.invoke%s(%s);" % (m.group(1), m.group(2), m.group(3).lower(), m.group(4)), text)

    def join(chunk):
        lines = chunk.split("\n")
        code_only = re.sub(r"//[^\n]*|/\*.*?\*/", "", chunk, flags=re.S)
        out = []
        i = 0
        while i < len(lines):
            m = re.fullmatch(r"(\t*)int (\w+) = (\w+)\.size\(\);", lines[i])
            if m:
                name, cv = m.group(2), m.group(3)
                j = i + 1
                patches = []
                while j < len(lines):
                    mm = re.fullmatch(r"(\t*)patchBranch\(%s, (.+), %s\);" % (re.escape(cv), re.escape(name)),
                                      lines[j])
                    if not mm:
                        break
                    patches.append("%spatchBranch(%s, %s, %s.size());" % (mm.group(1), cv, mm.group(2), cv))
                    j += 1
                uses = len(re.findall(r"\b%s\b" % re.escape(name), code_only))
                if patches and uses == 1 + len(patches):
                    out.extend(patches)
                    i = j
                    continue
            out.append(lines[i])
            i += 1
        return "\n".join(out)
    return "\n\t}\n".join(join(c) for c in text.split("\n\t}\n"))


if __name__ == "__main__":
    extra = []
    argv = sys.argv[1:]
    while argv and argv[0] == "--var":
        extra.append(argv[1])
        argv = argv[2:]
    for path in argv:
        src = open(path, encoding="utf-8").read()
        new, todo = convert(pre(src), extra)
        open(path, "w", encoding="utf-8").write(new)
        for t in todo:
            print("TODO %s: %s" % (path.rsplit("/", 1)[-1], t))
        for n, line in enumerate(new.splitlines(), 1):
            if re.search(r"\.add\(Opcode\.|emitU2\(|patchBranch\(|\.size\(\);|emitLdc\(|emitIntConst\(|"
                         r"\w+\.add\(\w+\);|List<Integer>|int\[\]", line):
                print("CHECK %s:%d: %s" % (path.rsplit("/", 1)[-1], n, line.strip()))
