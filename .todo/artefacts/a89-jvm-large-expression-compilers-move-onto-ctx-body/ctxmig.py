"""Moves a compiler from `ctx.emit`/`ctx.emitU2` onto `ctx.body` -- the mechanical part.

    python3 ctxmig.py FILE...

Straight-line instructions map one to one (`ctx.emit(Opcode.ALOAD); ctx.emit(s)` ->
`ctx.body.aload(s)`, `ctx.emit(Opcode.INVOKESTATIC); ctx.emitU2(r.index())` ->
`ctx.body.invokestatic(r.entry())`), keeping the emitted form: a `bipush`/`sipush` becomes
`loadConstant` only where that writes the same bytes, a short-form local (`ALOAD_0`) is left
for the hand. Branches become labels where the position is used only in the shapes below;
everything else is left as it was and printed, for the hand:

- `int V = ctx.code.size(); <branch with placeholder>` + `patchBranch(ctx, V, ctx.code.size())`
  -> `MethodCode.Label V = ctx.body.newLabel(); ctx.body.br(V)` + `ctx.body.labelBinding(V)`;
- `List<Integer> L = new ArrayList<>()`, `L.add(ctx.code.size()); <branch>`, and a loop patching
  every element to `ctx.code.size()` -> one label;
- `int V = ctx.code.size();` whose only uses are backward gotos `emitU2((V - W) & 0xFFFF)`
  -> `newBoundLabel()`.

Run javac after it (`../a85-.../jc.sh`, `fix.py` for the `.entry()` flavours).
"""
import re
import sys

BRANCHES = {
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
LOCALS = {"ALOAD", "ASTORE", "ILOAD", "ISTORE", "LLOAD", "LSTORE", "FLOAD", "FSTORE", "DLOAD", "DSTORE"}
NO_OPERAND = set("""ACONST_NULL ICONST_M1 ICONST_0 ICONST_1 ICONST_2 ICONST_3 ICONST_4 ICONST_5 LCONST_0 LCONST_1
FCONST_0 FCONST_1 FCONST_2 DCONST_0 DCONST_1 IALOAD LALOAD FALOAD DALOAD AALOAD BALOAD CALOAD SALOAD IASTORE LASTORE
FASTORE DASTORE AASTORE BASTORE CASTORE SASTORE POP POP2 DUP DUP_X1 DUP_X2 DUP2 DUP2_X1 SWAP IADD LADD FADD
DADD ISUB LSUB FSUB DSUB IMUL LMUL FMUL DMUL IDIV LDIV FDIV DDIV IREM LREM INEG LNEG FNEG DNEG ISHL LSHL
ISHR LSHR IUSHR LUSHR IAND LAND IOR LOR IXOR LXOR I2L I2F I2D L2I L2F L2D F2I F2D D2I D2L D2F I2B I2C I2S LCMP
FCMPL FCMPG DCMPL DCMPG IRETURN LRETURN FRETURN DRETURN ARETURN ARRAYLENGTH ATHROW MONITORENTER
MONITOREXIT""".split())
SPECIAL = {"RETURN": "return_"}
ATYPE = {"4": "BOOLEAN", "5": "CHAR", "6": "FLOAT", "7": "DOUBLE", "8": "BYTE", "9": "SHORT", "10": "INT", "11": "LONG",
         "ArrayType.T_BOOLEAN": "BOOLEAN", "ArrayType.T_CHAR": "CHAR", "ArrayType.T_FLOAT": "FLOAT",
         "ArrayType.T_DOUBLE": "DOUBLE", "ArrayType.T_BYTE": "BYTE", "ArrayType.T_SHORT": "SHORT",
         "ArrayType.T_INT": "INT", "ArrayType.T_LONG": "LONG"}

CTX = r"(?:this\.)?ctx"
EMIT_RE = re.compile(r"^(\s*)(%s)\.(emit|emitU2)\((.*)\);\s*(//.*)?$" % CTX, re.S)


def balanced(s):
    depth = 0
    in_str = False
    i = 0
    while i < len(s):
        c = s[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c == "/" and s[i:i + 2] == "//":
            break
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        i += 1
    return depth == 0


def statements(lines):
    """Yields (first, last, joined text) for every line group that is one complete statement
    starting with ctx.emit / ctx.emitU2."""
    i = 0
    out = {}
    while i < len(lines):
        if re.match(r"^\s*%s\.emit(U2)?\(" % CTX, lines[i]):
            j = i
            text = lines[i]
            while not (balanced(text) and re.search(r";\s*(//.*)?$", text)) and j + 1 < len(lines):
                j += 1
                text += "\n" + lines[j]
            out[i] = (i, j, text)
            i = j + 1
        else:
            i += 1
    return out


def parse(text):
    m = EMIT_RE.match(text)
    if not m:
        return None
    arg = re.sub(r"\s*\n\s*", "", m.group(4)).strip()
    return {"indent": m.group(1), "ctx": m.group(2), "kind": m.group(3), "arg": arg,
            "comment": m.group(5)}


def entry_of(arg):
    """The entry expression for a u2 pool operand `x.index()`, or None."""
    m = re.fullmatch(r"(.*)\.index\(\)", arg, re.S)
    if m and balanced(m.group(1)):
        return m.group(1) + ".entry()"
    return None


def convert_straight(lines, report, path):
    stm = statements(lines)
    out = []
    i = 0
    while i < len(lines):
        if i not in stm:
            out.append(lines[i])
            i += 1
            continue
        first, last, text = stm[i]
        p = parse(text)
        if p is None or p["kind"] != "emit" or not p["arg"].startswith("Opcode."):
            out.extend(lines[first:last + 1])
            if p is not None and p["kind"] == "emit" and not re.fullmatch(r"Opcode\.\w+", p["arg"]):
                report.append("%s:%d emit(%s)" % (path, first + 1, p["arg"]))
            i = last + 1
            continue
        op = p["arg"][len("Opcode."):]
        ind, c, comments = p["indent"], p["ctx"], [p["comment"]] if p["comment"] else []

        def operand(k):
            """The k-th statement after the opcode (1-based), must be the next line(s)."""
            nxt = last + 1
            res = None
            for _ in range(k):
                if nxt not in stm:
                    return None
                f2, l2, t2 = stm[nxt]
                res = (f2, l2, parse(t2))
                if res[2] is None:
                    return None
                nxt = l2 + 1
            return res

        def emit(call, end_line):
            cm = [x for x in comments if x]
            line = "%s%s.body.%s;" % (ind, c, call)
            if cm:
                line += " " + " ".join(cm)
            out.append(line)
            return end_line + 1

        if op in NO_OPERAND:
            i = emit(op.lower() + "()", last)
            continue
        if op in SPECIAL:
            i = emit(SPECIAL[op] + "()", last)
            continue
        if op in LOCALS:
            o = operand(1)
            if o and o[2]["kind"] == "emit":
                if o[2]["comment"]:
                    comments.append(o[2]["comment"])
                i = emit("%s(%s)" % (op.lower(), o[2]["arg"]), o[1])
                continue
        elif op in ("BIPUSH", "SIPUSH"):
            o = operand(1)
            want = "emit" if op == "BIPUSH" else "emitU2"
            if o and o[2]["kind"] == want:
                # loadConstant writes the shortest form: the same bytes only for a value
                # past iconst's range (and, for sipush, past bipush's).
                try:
                    v = int(o[2]["arg"], 0)
                except ValueError:
                    v = None
                lo, hi = (-128, 127) if op == "BIPUSH" else (-32768, 32767)
                if v is not None and lo <= v <= hi and not -1 <= v <= 5 and (op == "BIPUSH" or not -128 <= v <= 127):
                    if o[2]["comment"]:
                        comments.append(o[2]["comment"])
                    i = emit("loadConstant(%s)" % o[2]["arg"], o[1])
                    continue
        elif op == "NEWARRAY":
            o = operand(1)
            if o and o[2]["kind"] == "emit" and o[2]["arg"] in ATYPE:
                if o[2]["comment"]:
                    comments.append(o[2]["comment"])
                i = emit("newarray(TypeKind.%s)" % ATYPE[o[2]["arg"]], o[1])
                continue
        elif op in INDEXED:
            o = operand(1)
            if o and o[2]["kind"] == "emitU2":
                e = entry_of(o[2]["arg"])
                if e:
                    if o[2]["comment"]:
                        comments.append(o[2]["comment"])
                    i = emit("%s(%s)" % (INDEXED[op], e), o[1])
                    continue
        elif op == "LDC":
            o = operand(1)
            if o and o[2]["kind"] == "emit":
                e = entry_of(o[2]["arg"])
                if e:
                    i = emit("ldc(%s)" % e, o[1])
                    continue
        elif op == "INVOKEINTERFACE":
            o1, o2, o3 = operand(1), operand(2), operand(3)
            if o1 and o3 and o1[2]["kind"] == "emitU2" and o3[2]["arg"] == "0":
                e = entry_of(o1[2]["arg"])
                if e:
                    i = emit("invokeinterface(%s)" % e.replace(".entry()", ".interfaceMethodRefEntry()"), o3[1])
                    continue
        elif op in BRANCHES:
            o = operand(1)
            if o and o[2]["kind"] == "emitU2":
                if o[2]["arg"] == "0":
                    i = emit("%s(@@FWD@@)" % BRANCHES[op], o[1])
                    continue
                m = re.fullmatch(r"\((\w+) - (\w+)\) & 0xFFFF", o[2]["arg"])
                if m:
                    i = emit("%s(@@BACK %s %s@@)" % (BRANCHES[op], m.group(1), m.group(2)), o[1])
                    continue
        report.append("%s:%d left: %s" % (path, first + 1, text.strip()))
        out.extend(lines[first:last + 1])
        i = last + 1
    return out


def scope_end(lines, decl):
    """The last line of the block a declaration at line decl lives in."""
    ind = len(lines[decl]) - len(lines[decl].lstrip("\t"))
    for k in range(decl + 1, len(lines)):
        s = lines[k]
        if s.strip() and (len(s) - len(s.lstrip("\t"))) < ind:
            return k
    return len(lines) - 1


def uses(lines, name, a, b):
    return [k for k in range(a, b + 1) if re.search(r"(?<![\w.])%s\b" % re.escape(name), lines[k])]


def convert_branches(lines, report, path):
    # Forward: int V = ctx.code.size(); + branch placeholder.
    changed = True
    while changed:
        changed = False
        for k in range(len(lines) - 1):
            m = re.match(r"^(\s*)int (\w+) = ((?:this\.)?ctx)\.code\.size\(\);\s*$", lines[k])
            if not m or "@@FWD@@" not in lines[k + 1]:
                continue
            ind, v, c = m.groups()
            end = scope_end(lines, k)
            us = uses(lines, v, k + 1, end)
            patch_re = re.compile(r"^(\s*)(?:JvmEmitHelper\.)?patchBranch\(%s, %s, %s\.code\.size\(\)\);\s*$"
                                  % (re.escape(c), v, re.escape(c)))
            patches = [u for u in us if patch_re.match(lines[u])]
            others = [u for u in us if u != k + 1 and u not in patches]
            if len(patches) != 1 or others:
                continue
            lines[k] = "%sMethodCode.Label %s = %s.body.newLabel();" % (ind, v, c)
            lines[k + 1] = lines[k + 1].replace("@@FWD@@", v)
            pm = patch_re.match(lines[patches[0]])
            lines[patches[0]] = "%s%s.body.labelBinding(%s);" % (pm.group(1), c, v)
            changed = True
    # Forward to a list of positions.
    for k in range(len(lines)):
        m = re.match(r"^(\s*)(?:final )?List<Integer> (\w+) = new ArrayList<>\(\);\s*$", lines[k])
        if not m:
            continue
        ind, v = m.groups()
        end = scope_end(lines, k)
        us = uses(lines, v, k + 1, end)
        adds, loops = [], []
        ok = True
        c = None
        for u in us:
            am = re.match(r"^\s*%s\.add\(((?:this\.)?ctx)\.code\.size\(\)\);\s*$" % v, lines[u])
            if am and u + 1 < len(lines) and "@@FWD@@" in lines[u + 1]:
                adds.append(u)
                c = am.group(1)
                continue
            lm = re.match(r"^(\s*)for \(int (\w+) : %s\) \{?\s*$" % v, lines[u])
            if lm:
                q = lm.group(2)
                body = lines[u + 1].strip()
                if re.fullmatch(r"(?:JvmEmitHelper\.)?patchBranch\((?:this\.)?ctx, %s, (?:this\.)?ctx\.code\.size\(\)\);" % q,
                                body) and lines[u + 2].strip() == "}":
                    loops.append((u, lm.group(1)))
                    continue
            ok = False
        if not ok or not adds or len(loops) != 1:
            if adds:
                report.append("%s:%d list %s left" % (path, k + 1, v))
            continue
        lines[k] = "%sMethodCode.Label %s = %s.body.newLabel();" % (ind, v, c)
        for u in adds:
            lines[u] = None
            lines[u + 1] = lines[u + 1].replace("@@FWD@@", v)
        u, lind = loops[0]
        lines[u] = "%s%s.body.labelBinding(%s);" % (lind, c, v)
        lines[u + 1] = None
        lines[u + 2] = None
        lines[:] = [l for l in lines if l is not None]
        return convert_branches(lines, report, path)
    # Backward: int V = ctx.code.size(); used only by gotos (V - W) & 0xFFFF.
    for k in range(len(lines)):
        m = re.match(r"^(\s*)int (\w+) = ((?:this\.)?ctx)\.code\.size\(\);\s*$", lines[k])
        if not m:
            continue
        ind, v, c = m.groups()
        end = scope_end(lines, k)
        us = uses(lines, v, k + 1, end)
        backs = [u for u in us if "@@BACK %s " % v in lines[u]]
        if not backs or len(backs) != len(us):
            continue
        lines[k] = "%sMethodCode.Label %s = %s.body.newBoundLabel();" % (ind, v, c)
        for u in backs:
            bm = re.search(r"@@BACK %s (\w+)@@" % v, lines[u])
            w = bm.group(1)
            lines[u] = lines[u].replace(bm.group(0), v)
            # drop `int W = ctx.code.size();` right before, if W is used nowhere else
            if u > 0 and re.match(r"^\s*int %s = (?:this\.)?ctx\.code\.size\(\);\s*$" % w, lines[u - 1]) and \
                    len(uses(lines, w, u - 1, scope_end(lines, u - 1))) == 2:
                lines[u - 1] = None
        lines[:] = [l for l in lines if l is not None]
        return convert_branches(lines, report, path)
    for k, l in enumerate(lines):
        if "@@" in l:
            report.append("%s:%d branch left: %s" % (path, k + 1, l.strip()))
    return lines


def restore_markers(lines):
    """A branch no pattern resolved goes back to its byte form."""
    out = []
    for l in lines:
        m = re.match(r"^(\s*)((?:this\.)?ctx)\.body\.(\w+)\(@@FWD@@\);(.*)$", l)
        if m:
            op = [k for k, v in BRANCHES.items() if v == m.group(3)][0]
            out.append("%s%s.emit(Opcode.%s);%s" % (m.group(1), m.group(2), op, m.group(4)))
            out.append("%s%s.emitU2(0);" % (m.group(1), m.group(2)))
            continue
        m = re.match(r"^(\s*)((?:this\.)?ctx)\.body\.(\w+)\(@@BACK (\w+) (\w+)@@\);(.*)$", l)
        if m:
            op = [k for k, v in BRANCHES.items() if v == m.group(3)][0]
            out.append("%s%s.emit(Opcode.%s);%s" % (m.group(1), m.group(2), op, m.group(6)))
            out.append("%s%s.emitU2((%s - %s) & 0xFFFF);" % (m.group(1), m.group(2), m.group(4), m.group(5)))
            continue
        out.append(l)
    return out


CHAIN_RE = re.compile(r"^(\t+)((?:this\.)?ctx)\.body\.(\w+\(.*\));$")


# A chain ends at a branch or where control leaves: the next instruction starts a line.
ENDS_CHAIN = list(BRANCHES.values()) + ["athrow", "areturn", "ireturn", "lreturn", "dreturn", "freturn", "return_"]


def chain(lines, width=100):
    """Joins consecutive instruction statements into one chained call per line, as the
    hand-written slices read (`ctx.body.aload(a).iload(i).aaload();`); a label binding
    stays on its own line."""
    out = []
    for l in lines:
        m = CHAIN_RE.match(l)
        if m and out and not m.group(3).startswith("labelBinding("):
            pm = CHAIN_RE.match(out[-1])
            if pm and pm.group(1) == m.group(1) and pm.group(2) == m.group(2) \
                    and not pm.group(3).startswith("labelBinding(") and "//" not in out[-1] \
                    and not re.search(r"\.(%s)\([^()]*\);$" % "|".join(ENDS_CHAIN), out[-1]):
                joined = out[-1][:-1] + "." + m.group(3) + ";"
                if len(joined.expandtabs(4)) <= width:
                    out[-1] = joined
                    continue
        out.append(l)
    return out


def fix_imports(text):
    lines = text.split("\n")
    idx = [i for i, l in enumerate(lines) if l.startswith("import ")]
    last = idx[-1]
    body = "\n".join(lines[last + 1:])
    out = []
    for i, l in enumerate(lines):
        if i <= last and l == "import am.ik.jvm.Opcode;" and not re.search(r"(?<![\w.])Opcode\.", body):
            continue
        if i <= last and l == "import am.ik.jvm.ArrayType;" and not re.search(r"(?<![\w.])ArrayType\.", body):
            continue
        out.append(l)
    lines = out
    wanted = []
    if re.search(r"(?<![\w.])TypeKind\.", body):
        wanted.append("import java.lang.classfile.TypeKind;")
    if re.search(r"(?<![\w.])MethodCode\.", body):
        wanted.append("import am.ik.jvm.MethodCode;")
    for entry in ("ClassEntry", "MethodRefEntry", "FieldRefEntry", "InterfaceMethodRefEntry"):
        if re.search(r"(?<![\w.])%s\b" % entry, body):
            wanted.append("import java.lang.classfile.constantpool.%s;" % entry)
    for w in wanted:
        if w in lines:
            continue
        prefix = "import java." if w.startswith("import java.") else "import am."
        group = [i for i, l in enumerate(lines) if l.startswith(prefix) and not l.startswith("import static")]
        if group:
            pos = group[-1] + 1
            for i in group:
                if lines[i].rstrip(";") > w.rstrip(";"):
                    pos = i
                    break
            lines.insert(pos, w)
        else:
            first = [i for i, l in enumerate(lines) if l.startswith("import ")][0]
            lines[first:first] = [w, ""]
    return "\n".join(lines)


HELPER_RE = re.compile(r"\n(?:\t/\*\*[^\n]*\*/\n)?\t(?:private )?static int (\w+)\(JvmLispCompiler\.Ctx ctx, int opcode\) \{\n"
                       r"\t\tint pos = ctx\.code\.size\(\);\n\t\tctx\.emit\(opcode\);\n\t\tctx\.emitU2\(0\);\n"
                       r"\t\treturn pos;\n\t\}\n")


def inline_branch_helpers(text):
    """`int v = branch(ctx, Opcode.X)` (a file's own placeholder-branch helper) -> the
    three-statement form the branch rules read; the helper goes once nothing calls it."""
    for m in list(HELPER_RE.finditer(text)):
        name = m.group(1)
        text = re.sub(r"(?m)^(\s*)int (\w+) = %s\(((?:this\.)?ctx), Opcode\.(\w+)\);$" % name,
                      lambda g: "%sint %s = %s.code.size();\n%s%s.emit(Opcode.%s);\n%s%s.emitU2(0);" % (
                          g.group(1), g.group(2), g.group(3), g.group(1), g.group(3), g.group(4), g.group(1),
                          g.group(3)), text)
        text = re.sub(r"(?m)^(\s*)(\w+)\.add\(%s\(((?:this\.)?ctx), Opcode\.(\w+)\)\);$" % name,
                      lambda g: "%s%s.add(%s.code.size());\n%s%s.emit(Opcode.%s);\n%s%s.emitU2(0);" % (
                          g.group(1), g.group(2), g.group(3), g.group(1), g.group(3), g.group(4), g.group(1),
                          g.group(3)), text)
        if not re.search(r"(?<![\w.])%s\(" % name, text.replace(m.group(0), "")):
            text = text.replace(m.group(0), "\n", 1)
    return text


def main():
    report = []
    for path in sys.argv[1:]:
        src = inline_branch_helpers(open(path, encoding="utf-8").read())
        lines = src.split("\n")
        lines = convert_straight(lines, report, path)
        lines = convert_branches(lines, report, path)
        lines = restore_markers(lines)
        lines = chain(lines)
        text = fix_imports("\n".join(lines))
        open(path, "w", encoding="utf-8").write(text)
        left = len(re.findall(r"(?<![\w])(?:this\.)?ctx\.emit(?:U2)?\(", text))
        print("%s: %d ctx.emit/emitU2 left" % (path.rsplit("/", 1)[-1], left))
    for r in report:
        print(r)


if __name__ == "__main__":
    main()
