"""Moves a JvmAsm-based builder onto am.ik.jvm.MethodCode -- the mechanical part.

    python3 mig.py [--asm NAME] FILE...

Extends a84's migrate_asm.py: two-line `op(Opcode.X); u2(e.index())` pairs, every zero-operand
op, the ldc family, newarray kinds, pool facade calls with a variable pool, label-typed
parameters, and `new XMethod(name, desc, maxStack, maxLocals, a.finish())` constructions.
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
    "LDC_W": "ldc", "INVOKEINTERFACE": "invokeinterface",
}
SPECIAL_OPS = {"RETURN": "return_"}
SHORT_LOCALS = {}
for kind in ("I", "L", "F", "D", "A"):
    for n in range(4):
        SHORT_LOCALS["%sLOAD_%d" % (kind, n)] = "%sload(%d)" % (kind.lower(), n)
        SHORT_LOCALS["%sSTORE_%d" % (kind, n)] = "%sstore(%d)" % (kind.lower(), n)
NEWARRAY = {"Int": "INT", "Long": "LONG", "Float": "FLOAT", "Double": "DOUBLE", "Short": "SHORT", "Char": "CHAR",
            "Byte": "BYTE"}
ATYPE = {"4": "BOOLEAN", "5": "CHAR", "6": "FLOAT", "7": "DOUBLE", "8": "BYTE", "9": "SHORT", "10": "INT", "11": "LONG"}

# A balanced-paren expression (up to three levels), no top-level comma.
P0 = r"[^()]*"
P1 = r"(?:[^()]|\(%s\))*" % P0
P2 = r"(?:[^()]|\(%s\))*" % P1
P3 = r"(?:[^()]|\(%s\))*" % P2
ARG = r"(?:[^(),]|\(%s\))+" % P3


def split_args(s):
    """Top-level comma split of a call's argument text (string literals skipped)."""
    out, depth, cur = [], 0, []
    i = 0
    while i < len(s):
        ch = s[i]
        if ch == '"':
            j = i + 1
            while j < len(s) and s[j] != '"':
                j += 2 if s[j] == chr(92) else 1
            cur.append(s[i:j + 1])
            i = j + 1
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
        i += 1
    if "".join(cur).strip():
        out.append("".join(cur).strip())
    return out


def call_span(text, open_idx):
    """Given the index of '(' return the index just past the matching ')'."""
    depth = 0
    i = open_idx
    in_str = False
    while i < len(text):
        ch = text[i]
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ValueError("unbalanced")


def rewrite_calls(text, name_re, fn):
    """Rewrites every call whose callee matches name_re (ending just before '(')."""
    out = []
    pos = 0
    for m in re.finditer(name_re, text):
        if m.start() < pos:
            continue
        open_idx = m.end() - 1
        assert text[open_idx] == "("
        end = call_span(text, open_idx)
        args = split_args(text[open_idx + 1:end - 1])
        rep = fn(m, args)
        if rep is None:
            continue
        out.append(text[pos:m.start()])
        out.append(rep)
        pos = end
    out.append(text[pos:])
    return "".join(out)


def utf8_literal(expr, pool):
    m = re.fullmatch(re.escape(pool) + r"\.addUtf8\((%s)\)" % P3, expr.strip(), re.S)
    return m.group(1).strip() if m else None


WRAPPERS = ["ClassConstant", "MethodrefConstant", "FieldrefConstant", "StringConstant", "IntegerConstant",
            "LongConstant", "DoubleConstant"]
ENTRY_TYPES = ["ClassEntry", "MethodRefEntry", "FieldRefEntry", "StringEntry", "IntegerEntry", "LongEntry",
               "DoubleEntry", "MemberRefEntry", "InterfaceMethodRefEntry", "LoadableConstantEntry"]


def fix_imports(text):
    """Removes imports nothing names any more and adds the ones the new code needs, each
    in sorted position within its existing group."""
    lines = text.split("\n")
    idx = [i for i, l in enumerate(lines) if l.startswith("import ")]
    if not idx:
        return text
    last = idx[-1]
    body = "\n".join(lines[last + 1:])

    def used(simple):
        return re.search(r"\b%s\b" % re.escape(simple), body) is not None

    out = []
    for i, l in enumerate(lines):
        if re.fullmatch(r"import (%s);" % "|".join(ENTRY_TYPES), l):
            continue
        m = re.fullmatch(r"import ([\w.]+)\.(\w+);", l)
        if i <= last and m and not l.startswith("import static"):
            simple = m.group(2)
            if m.group(1) in ("am.ik.jvm.ConstantPool", "java.lang.classfile.constantpool") and not used(simple):
                continue
            if l == "import am.ik.jvm.Opcode;" and not re.search(r"(?<![\w.])Opcode\.", body):
                continue
        out.append(l)
    lines = out
    wanted = []
    for t in ENTRY_TYPES:
        if used(t):
            wanted.append("import java.lang.classfile.constantpool.%s;" % t)
    if used("TypeKind"):
        wanted.append("import java.lang.classfile.TypeKind;")
    if used("MethodCode"):
        wanted.append("import am.ik.jvm.MethodCode;")
    for w in wanted:
        if w in lines:
            continue
        prefix = "import java." if w.startswith("import java.") else "import am."
        group = [i for i, l in enumerate(lines) if l.startswith(prefix) and not l.startswith("import static")]
        key = lambda l: l.rstrip(";")
        if group:
            pos = group[-1] + 1
            for i in group:
                if key(lines[i]) > key(w):
                    pos = i
                    break
            lines.insert(pos, w)
        else:
            first = [i for i, l in enumerate(lines) if l.startswith("import ")][0]
            if prefix == "import java.":
                lines[first:first] = [w, ""]
            else:
                lastimp = [i for i, l in enumerate(lines) if l.startswith("import ")][-1]
                lines[lastimp + 1:lastimp + 1] = ["", w]
    return "\n".join(lines)


def migrate(text, asm, asm_only=False):
    if not asm_only:
        text = migrate_pool(text)
    return migrate_asm(text, asm)


def migrate_pool(text):
    # --- pool facade ------------------------------------------------------------------
    for _ in range(3):
        def class_fn(m, args):
            pool = m.group(1)
            if len(args) == 1:
                lit = utf8_literal(args[0], pool)
                if lit is not None:
                    return "%s.classEntry(%s)" % (pool, lit)
                return "%s.entries().classEntry(%s.entry())" % (pool, args[0])
            return None
        text = rewrite_calls(text, r"((?:this\.)?\w+(?:\.\w+)*(?:\(\))?)\.addClass\(", class_fn)

        def ref_fn(kind):
            def fn(m, args):
                pool = m.group(1)
                if len(args) != 2:
                    return None
                owner, nat = args
                mm = re.fullmatch(re.escape(pool) + r"\.addNameAndType\((.*)\)", nat.strip(), re.S)
                if not mm:
                    return None
                nargs = split_args(mm.group(1))
                if len(nargs) != 2:
                    return None
                n = utf8_literal(nargs[0], pool)
                d = utf8_literal(nargs[1], pool)
                if n is None:
                    n = nargs[0] + ".entry().stringValue()"
                if d is None:
                    d = nargs[1] + ".entry().stringValue()"
                return "%s.%s(%s, %s, %s)" % (pool, kind, owner, n, d)
            return fn
        text = rewrite_calls(text, r"((?:this\.)?\w+(?:\.\w+)*(?:\(\))?)\.addMethodref\(", ref_fn("methodRef"))
        text = rewrite_calls(text, r"((?:this\.)?\w+(?:\.\w+)*(?:\(\))?)\.addInterfaceMethodref\(", ref_fn("interfaceMethodRef"))
        text = rewrite_calls(text, r"((?:this\.)?\w+(?:\.\w+)*(?:\(\))?)\.addFieldref\(", ref_fn("fieldRef"))

    def string_fn(m, args):
        pool = m.group(1)
        if len(args) != 1:
            return None
        lit = utf8_literal(args[0], pool)
        if lit is not None:
            return "%s.stringEntry(%s)" % (pool, lit)
        return "%s.stringEntry(%s)" % (pool, args[0])
    text = rewrite_calls(text, r"((?:this\.)?\w+(?:\.\w+)*(?:\(\))?)\.addString\(", string_fn)
    text = re.sub(r"\b((?:this\.)?\w+)\.addInteger\(", r"\1.entries().intEntry(", text)
    text = re.sub(r"\b((?:this\.)?\w+)\.addLong\(", r"\1.entries().longEntry(", text)
    text = re.sub(r"\b((?:this\.)?\w+)\.addDouble\(", r"\1.entries().doubleEntry(", text)

    # --- types ------------------------------------------------------------------------
    text = re.sub(r"\b(?:ConstantPool\.|am\.ik\.jvm\.ConstantPool\.)?ClassConstant\b", "ClassEntry", text)
    text = re.sub(r"\b(?:ConstantPool\.|am\.ik\.jvm\.ConstantPool\.)?MethodrefConstant\b", "MethodRefEntry", text)
    text = re.sub(r"\b(?:ConstantPool\.|am\.ik\.jvm\.ConstantPool\.)?FieldrefConstant\b", "FieldRefEntry", text)
    text = re.sub(r"\b(?:ConstantPool\.|am\.ik\.jvm\.ConstantPool\.)?StringConstant\b", "StringEntry", text)
    text = re.sub(r"\b(?:ConstantPool\.|am\.ik\.jvm\.ConstantPool\.)?IntegerConstant\b", "IntegerEntry", text)
    text = re.sub(r"\b(?:ConstantPool\.|am\.ik\.jvm\.ConstantPool\.)?LongConstant\b", "LongEntry", text)
    text = re.sub(r"\b(?:ConstantPool\.|am\.ik\.jvm\.ConstantPool\.)?DoubleConstant\b", "DoubleEntry", text)

    return text


def migrate_asm(text, asm):
    # --- assembler --------------------------------------------------------------------
    text = re.sub(r"\b%s (\w+) = new %s\(\);" % (asm, asm), r"MethodCode \1 = new MethodCode();", text)
    text = re.sub(r"\bnew %s\(\)" % asm, "new MethodCode()", text)
    text = re.sub(r"\b%s (\w+)\b" % asm, r"MethodCode \1", text)
    text = re.sub(r"\bint (\w+) = ((?:this\.)?\w+)\.label\(\);", r"MethodCode.Label \1 = \2.newLabel();", text)
    text = re.sub(r"\b((?:this\.)?\w+)\.label\(\)", r"\1.newLabel()", text)
    text = re.sub(r"\b((?:this\.)?\w+)\.bind\(", r"\1.labelBinding(", text)
    text = re.sub(r"\b((?:this\.)?\w+)\.branch\(Opcode\.(\w+), ",
                  lambda m: "%s.%s(" % (m.group(1), BRANCHES[m.group(2)]), text)
    # op + u2 pairs
    text = re.sub(r"\b((?:this\.)?\w+)\.op0?\(Opcode\.(\w+)\);\s*\n(\s*)\1\.u2\((%s)\.index\(\)\);" % P3,
                  lambda m: ("%s.%s(%s);" % (m.group(1), INDEXED[m.group(2)], m.group(4)))
                  if m.group(2) in INDEXED else m.group(0), text)

    def op_fn(m):
        recv, name = m.group(1), m.group(2)
        if name in SHORT_LOCALS:
            return "%s.%s" % (recv, SHORT_LOCALS[name])
        if name in INDEXED or name in BRANCHES:
            return m.group(0)
        if name in SPECIAL_OPS:
            return "%s.%s()" % (recv, SPECIAL_OPS[name])
        return "%s.%s()" % (recv, name.lower())
    text = re.sub(r"\b((?:this\.)?\w+)\.op0?\(Opcode\.(\w+)\)", op_fn, text)
    text = re.sub(r"\b((?:this\.)?\w+)\.ldc\((%s)\.index\(\)\)" % ARG, r"\1.ldc(\2)", text)
    text = re.sub(r"\b((?:this\.)?\w+)\.iconst\(", r"\1.loadConstant(", text)
    text = re.sub(r"\b((?:this\.)?\w+)\.(?:ldcString|ldcInt|ldcClass|ldc2Long|ldc2Double)\(", r"\1.ldc(", text)
    text = re.sub(r"\b((?:this\.)?\w+)\.anew\(", r"\1.new_(", text)
    text = re.sub(r"\b((?:this\.)?\w+)\.aconstNull\(\)", r"\1.aconst_null()", text)
    text = re.sub(r"\b((?:this\.)?\w+)\.lconst0\(\)", r"\1.lconst_0()", text)
    text = re.sub(r"\b((?:this\.)?\w+)\.newarray(Int|Long|Float|Double|Short|Char|Byte)\(\)",
                  lambda m: "%s.newarray(TypeKind.%s)" % (m.group(1), NEWARRAY[m.group(2)]), text)
    text = re.sub(r"\b((?:this\.)?\w+)\.newarray\((\d+)\)",
                  lambda m: "%s.newarray(TypeKind.%s)" % (m.group(1), ATYPE[m.group(2)]), text)
    text = re.sub(r"\b((?:this\.)?\w+)\.invokeinterface\((%s), *\d+\)" % ARG, r"\1.invokeinterface(\2)", text)

    # --- raw access to the code list
    names = set(re.findall(r"\bMethodCode (\w+)\b", text))
    for n in names:
        text = re.sub(r"(?<![\w.])((?:this\.)?%s)\.code\b(?!\()" % n, r"\1.code()", text)

    # --- label-typed parameters -------------------------------------------------------
    for _ in range(4):
        text = retype_label_params(text)

    # --- method records ---------------------------------------------------------------
    def record_fn(m, args):
        if len(args) == 5 and re.fullmatch(r"(?:this\.)?\w+\.(?:finish\(\)|code)", args[4]):
            recv = args[4].rsplit(".", 1)[0]
            return "new %s(%s, %s, %s)" % (m.group(1), args[0], args[1], recv)
        return None
    text = rewrite_calls(text, r"new (\w+Method)\(", record_fn)

    text = re.sub(r"\brecord (\w+Method)\((Utf8Constant \w+), (Utf8Constant \w+), int maxStack, int maxLocals,\s*"
                  r"List<Integer> code\)", r"record \1(\2, \3, MethodCode code)", text)
    return text


METHOD_DECL = re.compile(r"\n(\t+)(?:(?:private|static|final|public|protected)\s+)*(?:void|[\w.<>\[\]]+)\s+(\w+)\(([^)]*)\)\s*\{")


def retype_label_params(text):
    out = []
    pos = 0
    for m in METHOD_DECL.finditer(text):
        if m.start() < pos:
            continue
        params = m.group(3)
        if "int " not in params:
            continue
        body_start = m.end() - 1
        depth = 0
        i = body_start
        while i < len(text):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        body = text[body_start:i]
        new_params = params
        for p in re.findall(r"\bint (\w+)", params):
            used = (re.search(r"\.(?:labelBinding|position)\(%s\)" % p, body)
                    or re.search(r"\.(?:%s)\(%s\)" % ("|".join(BRANCHES.values()), p), body)
                    or re.search(r"exceptionCatch\([^;]*\b%s\b" % p, body))
            if used:
                new_params = re.sub(r"\bint %s\b" % p, "MethodCode.Label %s" % p, new_params)
        if new_params != params:
            s, e = m.span(3)
            out.append(text[pos:s])
            out.append(new_params)
            pos = e
    out.append(text[pos:])
    return "".join(out)


if __name__ == "__main__":
    argv = sys.argv[1:]
    asm = "JvmAsm"
    asm_only = False
    while argv and argv[0].startswith("--"):
        if argv[0] == "--asm":
            asm = argv[1]
            argv = argv[2:]
        elif argv[0] == "--asm-only":
            asm_only = True
            argv = argv[1:]
    for path in argv:
        text = open(path, encoding="utf-8").read()
        new = fix_imports(migrate(text, asm, asm_only))
        open(path, "w", encoding="utf-8").write(new)
        for i, line in enumerate(new.splitlines(), 1):
            if (asm in line or ".finish()" in line or re.search(r"\.op0?\(|\.u2\(|\.pos\(\)|addNameAndType", line)):
                print("CHECK %s:%d: %s" % (path.rsplit("/", 1)[-1], i, line.strip()))
