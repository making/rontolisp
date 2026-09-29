"""Moves a builder that assembles raw List<Integer> code onto am.ik.jvm.MethodCode -- the
mechanical part (a85's mig.py does the same for a builder on an assembler).

    python3 raw.py [--var NAME]... FILE...

The raw idiom: `code.add(Opcode.X)`, an operand as `emitU2(code, e.index())` or a following
`code.add(n)`, and a branch patched by POSITION (`int p = code.size(); code.add(Opcode.IFEQ);
emitU2(code, 0); ...; patchBranch(code, p, code.size())`). Rewritten here:

- every instruction to its typed call (`code.aload(1)`, `code.getstatic(e)`, `code.ldc(e)`,
  `code.loadConstant(n)`, `code.iinc(s, n)`, `code.newarray(TypeKind.INT)`, ...), a pool
  operand losing its `.index()`;
- a position read right before a branch to a label (`MethodCode.Label p = code.newLabel();
  code.ifeq(p);`), and `patchBranch(code, p, code.size())` to `code.labelBinding(p)`;
- a list of such positions all patched by ONE loop to ONE label (`code.ifnull(nils)` ...
  `code.labelBinding(nils)`);
- a backward branch (`patchBranch(code, g, top)` right after its `goto`) to a branch to a
  label bound where `int top = code.size()` stood (`code.newBoundLabel()`);
- a label named `...Pos` to the name without it, where the method does not use that name;
- `List<Integer> code` declarations and the helpers' parameters and return types to
  `MethodCode`, `JvmRuntimeBuilder.codeBytes(a)` returns to `a`, and the file's private raw
  helpers (`emitU2`, `emitLdc`, `patchBranch`, `emitIntConst`, `emitAload`, `emitAstore`) go.

The code variable is every name declared `List<Integer> NAME = new ArrayList<>()` or taken as a
`List<Integer> NAME` parameter, plus each `--var`. Left for a hand: an exception-table bound
(`newBoundLabel`, then `exceptionCatch`), a position kept in a sentinel (`int p = -1`: a
`@Nullable` label), an opcode passed as a value (`code.add(load)`: pass the slot), and a
`.index()` under a conditional. Prints every line still in the raw idiom. The pool wrappers are
mig.py's `migrate_pool` (`pool.py`); the method records' declared sizes, `records.py`.
"""
import re
import sys

BRANCH = {
    "IFEQ": "ifeq", "IFNE": "ifne", "IFLT": "iflt", "IFGE": "ifge", "IFGT": "ifgt", "IFLE": "ifle",
    "IF_ICMPEQ": "if_icmpeq", "IF_ICMPNE": "if_icmpne", "IF_ICMPLT": "if_icmplt", "IF_ICMPGE": "if_icmpge",
    "IF_ICMPGT": "if_icmpgt", "IF_ICMPLE": "if_icmple", "IF_ACMPEQ": "if_acmpeq", "IF_ACMPNE": "if_acmpne",
    "IFNULL": "ifnull", "IFNONNULL": "ifnonnull", "GOTO": "goto_",
}
INDEXED = {
    "INVOKESTATIC": "invokestatic", "INVOKEVIRTUAL": "invokevirtual", "INVOKESPECIAL": "invokespecial",
    "NEW": "new_", "INSTANCEOF": "instanceOf", "GETFIELD": "getfield", "PUTFIELD": "putfield",
    "GETSTATIC": "getstatic", "PUTSTATIC": "putstatic", "CHECKCAST": "checkcast", "ANEWARRAY": "anewarray",
    "LDC_W": "ldc", "LDC2_W": "ldc",
}
LOCAL = {k: k.lower() for k in ("ILOAD", "LLOAD", "FLOAD", "DLOAD", "ALOAD", "ISTORE", "LSTORE", "FSTORE",
                               "DSTORE", "ASTORE")}
SHORT_LOCAL = {}
for kind in "ILFDA":
    for n in range(4):
        SHORT_LOCAL["%sLOAD_%d" % (kind, n)] = "%sload(%d)" % (kind.lower(), n)
        SHORT_LOCAL["%sSTORE_%d" % (kind, n)] = "%sstore(%d)" % (kind.lower(), n)
SPECIAL = {"RETURN": "return_()"}
NOT_ZERO_OPERAND = set(BRANCH) | set(INDEXED) | set(LOCAL) | {"BIPUSH", "SIPUSH", "IINC", "NEWARRAY", "LDC",
                                                              "INVOKEINTERFACE", "MULTIANEWARRAY", "WIDE"}
ATYPE = {"4": "BOOLEAN", "5": "CHAR", "6": "FLOAT", "7": "DOUBLE", "8": "BYTE", "9": "SHORT", "10": "INT",
         "11": "LONG"}

STMT = re.compile(r"^(\s*)(.*?;)(\s*//.*)?$")


def parts(line):
    """(indent, statement, trailing comment) of a one-statement line, or None."""
    m = STMT.match(line)
    if not m:
        return None
    return m.group(1), m.group(2), m.group(3) or ""


def depth0_has(expr, chars):
    depth = 0
    in_str = False
    i = 0
    while i < len(expr):
        c = expr[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and c in chars:
            return True
        i += 1
    return False


def entry_of(expr):
    """`x.index()` -> `x` for a primary x; None where a hand must decide."""
    expr = expr.strip()
    if not expr.endswith(".index()"):
        return None
    base = expr[:-len(".index()")]
    if depth0_has(base, " ?:+-*/"):
        return None
    return base


def signed_byte(text):
    text = text.strip()
    if re.fullmatch(r"\d+", text):
        v = int(text)
        return str(v - 256 if v > 127 else v)
    if re.fullmatch(r"0x[0-9A-Fa-f]+", text):
        v = int(text, 16)
        return str(v - 256 if v > 127 else v)
    return None


KEYWORDS = {"if", "goto", "do", "for", "new", "try", "case", "else", "int", "char", "byte", "long", "this", "null",
            "true", "false"}


def rename_labels(text):
    """A label is not a position: ifDonePos -> ifDone, method by method, unless the method
    already uses that name."""
    def chunk_fn(chunk):
        for name in sorted(set(re.findall(r"\bMethodCode\.Label (\w+Pos) = ", chunk))):
            short = name[:-len("Pos")]
            if short and short not in KEYWORDS and not re.search(r"\b%s\b" % re.escape(short), chunk):
                chunk = re.sub(r"\b%s\b" % re.escape(name), short, chunk)
        return chunk
    return "\n\t}\n".join(chunk_fn(chunk) for chunk in text.split("\n\t}\n"))


def convert(text, extra_vars):
    names = set(extra_vars)
    names |= set(re.findall(r"\bList<Integer> (\w+) = new ArrayList<>\(\);", text))
    names |= set(re.findall(r"[(,]\s*List<Integer> (\w+)[,)]", text))
    lines = text.split("\n")
    label_lists = {}  # list name -> code var
    todo = []
    out = []
    i = 0

    def at(k):
        return lines[k] if k < len(lines) else ""

    while i < len(lines):
        line = lines[i]
        p = parts(line)
        if p is None:
            out.append(line)
            i += 1
            continue
        ind, st, cm = p
        done = False
        for v in names:
            V = re.escape(v)
            # a position read right before a branch
            cap = re.fullmatch(r"(?:(int) (\w+) = |(\w+) = |(\w+)\.add\()%s\.size\(\)\)?;" % V, st)
            if cap:
                b = parts(at(i + 1))
                u = parts(at(i + 2))
                bm = b and re.fullmatch(r"%s\.add\(Opcode\.(\w+)\);" % V, b[1])
                if bm and bm.group(1) in BRANCH and u and u[1] == "emitU2(%s, 0);" % v:
                    op = BRANCH[bm.group(1)]
                    if cap.group(1):
                        out.append("%sMethodCode.Label %s = %s.newLabel();%s" % (ind, cap.group(2), v, cm))
                        out.append("%s%s.%s(%s);%s" % (ind, v, op, cap.group(2), b[2] + u[2]))
                    elif cap.group(3):
                        out.append("%s%s = %s.newLabel();%s" % (ind, cap.group(3), v, cm))
                        out.append("%s%s.%s(%s);%s" % (ind, v, op, cap.group(3), b[2] + u[2]))
                    else:
                        label_lists[cap.group(4)] = v
                        out.append("%s%s.%s(%s);%s" % (ind, v, op, cap.group(4), cm + b[2] + u[2]))
                    i += 3
                    done = True
                    break
            m = re.fullmatch(r"patchBranch\(%s, (\w+), %s\.size\(\)\);" % (V, V), st)
            if m:
                out.append("%s%s.labelBinding(%s);%s" % (ind, v, m.group(1), cm))
                i += 1
                done = True
                break
            m = re.fullmatch(r"%s\.add\(Opcode\.(\w+)\);" % V, st)
            if m:
                op = m.group(1)
                n1 = parts(at(i + 1))
                if op in INDEXED and n1:
                    mm = re.fullmatch(r"emitU2\(%s, (.*)\);" % V, n1[1])
                    e = entry_of(mm.group(1)) if mm else None
                    if e is not None:
                        out.append("%s%s.%s(%s);%s" % (ind, v, INDEXED[op], e, cm + n1[2]))
                        i += 2
                        done = True
                        break
                if op == "INVOKEINTERFACE" and n1:
                    mm = re.fullmatch(r"emitU2\(%s, (.*)\);" % V, n1[1])
                    e = entry_of(mm.group(1)) if mm else None
                    n2, n3 = parts(at(i + 2)), parts(at(i + 3))
                    if (e is not None and n2 and n3 and re.fullmatch(r"%s\.add\(\d+\);" % V, n2[1])
                            and n3[1] == "%s.add(0);" % v):
                        out.append("%s%s.invokeinterface(%s);%s" % (ind, v, e, cm))
                        i += 4
                        done = True
                        break
                if op in LOCAL and n1:
                    mm = re.fullmatch(r"%s\.add\((.*)\);" % V, n1[1])
                    if mm and not mm.group(1).startswith("Opcode."):
                        out.append("%s%s.%s(%s);%s" % (ind, v, LOCAL[op], mm.group(1), cm + n1[2]))
                        i += 2
                        done = True
                        break
                if op == "BIPUSH" and n1:
                    mm = re.fullmatch(r"%s\.add\((.*)\);" % V, n1[1])
                    if mm and not mm.group(1).startswith("Opcode."):
                        val = mm.group(1)
                        mc = re.fullmatch(r"\(int\) ('.*')", val)
                        if mc:
                            val = mc.group(1)
                        out.append("%s%s.loadConstant(%s);%s" % (ind, v, val, cm + n1[2]))
                        i += 2
                        done = True
                        break
                if op == "SIPUSH" and n1:
                    mm = re.fullmatch(r"emitU2\(%s, (.*)\);" % V, n1[1])
                    if mm:
                        out.append("%s%s.loadConstant(%s);%s" % (ind, v, mm.group(1), cm + n1[2]))
                        i += 2
                        done = True
                        break
                if op == "IINC" and n1:
                    n2 = parts(at(i + 2))
                    ms = re.fullmatch(r"%s\.add\((.*)\);" % V, n1[1])
                    mv = n2 and re.fullmatch(r"%s\.add\((.*)\);" % V, n2[1])
                    if ms and mv and signed_byte(mv.group(1)) is not None:
                        out.append("%s%s.iinc(%s, %s);%s" % (ind, v, ms.group(1), signed_byte(mv.group(1)),
                                                              cm + n1[2] + n2[2]))
                        i += 3
                        done = True
                        break
                if op == "NEWARRAY" and n1:
                    mm = re.fullmatch(r"%s\.add\((\d+)\);" % V, n1[1])
                    if mm and mm.group(1) in ATYPE:
                        out.append("%s%s.newarray(TypeKind.%s);%s" % (ind, v, ATYPE[mm.group(1)], cm))
                        i += 2
                        done = True
                        break
            m = re.fullmatch(r"emitLdc\(%s, (.*)\);" % V, st)
            if m and entry_of(m.group(1)) is not None:
                out.append("%s%s.ldc(%s);%s" % (ind, v, entry_of(m.group(1)), cm))
                i += 1
                done = True
                break
            m = re.fullmatch(r"emitIntConst\(%s, (.*)\);" % V, st)
            if m:
                out.append("%s%s.loadConstant(%s);%s" % (ind, v, m.group(1), cm))
                i += 1
                done = True
                break
            m = re.fullmatch(r"emit(Aload|Astore)\(%s, (.*)\);" % V, st)
            if m:
                out.append("%s%s.%s(%s);%s" % (ind, v, m.group(1).lower(), m.group(2), cm))
                i += 1
                done = True
                break
        if not done:
            out.append(line)
            i += 1
    text = "\n".join(out)

    # the loops that patch a list of positions to one target (the line pass above has
    # already turned the loop's patchBranch into a labelBinding of the element)
    for lst, v in label_lists.items():
        loop = re.compile(r"\n(\t*)for \(int (\w+) : %s\) \{\n\t*%s\.labelBinding\(\2\);\n\t*\}"
                          % (re.escape(lst), re.escape(v)))
        found = loop.findall(text)
        decl = re.compile(r"\bList<Integer> %s = new ArrayList<>\(\);" % re.escape(lst))
        # one loop per declaration: each method declares its own list and binds it once
        if found and len(found) == len(decl.findall(text)):
            text = loop.sub(lambda m: "\n%s%s.labelBinding(%s);" % (m.group(1), v, lst), text)
            text = decl.sub("MethodCode.Label %s = %s.newLabel();" % (lst, v), text)
        else:
            todo.append("list %s: %d patch loops, %d declarations" % (lst, len(found), len(decl.findall(text))))

    # a backward branch: the position it jumps to becomes a label bound there, and the
    # branch names it directly (method by method: two methods may share a local's name)
    def backward(chunk):
        for v in names:
            V = re.escape(v)
            back = re.compile(r"\n(\t*)MethodCode\.Label (\w+) = %s\.newLabel\(\);\n\t*%s\.(\w+)\(\2\);\n"
                              r"\t*patchBranch\(%s, \2, (\w+)\);" % (V, V, V))
            for t in set(m.group(4) for m in back.finditer(chunk)):
                decl = re.compile(r"\bint %s = %s\.size\(\);" % (re.escape(t), V))
                uses = len(re.findall(r"\b%s\b" % re.escape(t), chunk))
                branches = len([m for m in back.finditer(chunk) if m.group(4) == t])
                if len(decl.findall(chunk)) == 1 and uses == branches + 1:
                    chunk = decl.sub("MethodCode.Label %s = %s.newBoundLabel();" % (t, v), chunk)
                    chunk = back.sub(lambda m: "\n%s%s.%s(%s);" % (m.group(1), v, m.group(3), m.group(4))
                                     if m.group(4) == t else m.group(0), chunk)
                else:
                    todo.append("backward target %s: %d declarations, %d uses" % (t, len(decl.findall(chunk)), uses))
        return chunk
    text = "\n\t}\n".join(backward(chunk) for chunk in text.split("\n\t}\n"))

    text = rename_labels(text)

    # every remaining zero-operand instruction, a lambda's body included
    for v in names:
        def zero(m):
            op = m.group(2)
            if op in SHORT_LOCAL:
                return "%s.%s" % (m.group(1), SHORT_LOCAL[op])
            if op in SPECIAL:
                return "%s.%s" % (m.group(1), SPECIAL[op])
            if op in NOT_ZERO_OPERAND:
                return m.group(0)
            return "%s.%s()" % (m.group(1), op.lower())
        text = re.sub(r"\b(%s)\.add\(Opcode\.(\w+)\)" % re.escape(v), zero, text)

    # declarations, parameters and returns
    for v in names:
        text = re.sub(r"\bList<Integer> %s = new ArrayList<>\(\);" % re.escape(v), "MethodCode %s = new MethodCode();" % v,
                      text)
        text = re.sub(r"([(,]\s*)List<Integer> %s([,)])" % re.escape(v), r"\1MethodCode %s\2" % v, text)
    text = re.sub(r"return JvmRuntimeBuilder\.codeBytes\((\w+)\);", r"return \1;", text)
    # a method that returns one of the bodies
    returning = set()
    bodies = names | set(re.findall(r"\bMethodCode (\w+) = new MethodCode\(\);", text))
    for m in re.finditer(r"\bList<Integer> (\w+)\(", text):
        body = text[m.end():]
        end = body.find("\n\t}\n")
        if any(re.search(r"\breturn %s;" % re.escape(v), body[:end]) for v in bodies):
            returning.add(m.group(1))
    for name in returning:
        text = re.sub(r"\bList<Integer> %s\(" % re.escape(name), "MethodCode %s(" % name, text)
    # the raw helpers, now without a caller
    for helper in ("emitU2", "emitLdc", "patchBranch", "emitIntConst", "emitAload", "emitAstore"):
        text = re.sub(r"\n\tprivate static void %s\((?:List<Integer>|MethodCode) \w+, [^)]*\) \{\n.*?\n\t\}\n"
                      % helper, "\n", text, flags=re.S)
    return text, todo


if __name__ == "__main__":
    argv = sys.argv[1:]
    extra = []
    while argv and argv[0] == "--var":
        extra.append(argv[1])
        argv = argv[2:]
    for path in argv:
        src = open(path, encoding="utf-8").read()
        new, todo = convert(src, extra)
        open(path, "w", encoding="utf-8").write(new)
        for t in todo:
            print("TODO %s: %s" % (path.rsplit("/", 1)[-1], t))
        for n, line in enumerate(new.splitlines(), 1):
            if re.search(r"\.add\(Opcode\.|emitU2\(|patchBranch\(|\.size\(\);|emitLdc\(|emitIntConst\(|"
                         r"\w+\.add\(\w+\);|List<Integer>", line):
                print("CHECK %s:%d: %s" % (path.rsplit("/", 1)[-1], n, line.strip()))
