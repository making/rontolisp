"""pool2.py FILE...: every ConstantPool wrapper to the java.lang.classfile entry it wraps, and the
add* facades to the entry facades, minted in the same order (a86's pool.py plus Utf8 and
NameAndType, which it left):

  cp.addUtf8(s)                                   -> cp.utf8Entry(s)
  cp.addClass(cp.addUtf8(s)) / cp.addClass(u)     -> cp.classEntry(s) / cp.classEntry(u)
  cp.addNameAndType(n, d)                         -> cp.entries().nameAndTypeEntry(n, d)
  cp.addMethodref(c, cp.addNameAndType(n, d))     -> cp.methodRef(c, n, d)  (strings when both
                                                     names were cp.addUtf8(literal expression))
  cp.addMethodref(c, nat)                         -> cp.entries().methodRefEntry(c, nat)
  (addFieldref, addInterfaceMethodref alike), cp.addString(x) -> cp.stringEntry(x),
  cp.addInteger/addLong/addDouble(v)              -> cp.entries().intEntry/longEntry/doubleEntry(v)

and the wrapper types to entry types (MethodrefConstant -> MethodRefEntry: an interface method
held in one needs a hand). Then javac's errors say where an `.entry()` is left on an entry: run
unentry2.py over them. Prints nothing; the imports follow."""
import re
import sys

P0 = r"[^()]*"
P1 = r"(?:[^()]|\(%s\))*" % P0
P2 = r"(?:[^()]|\(%s\))*" % P1
P3 = r"(?:[^()]|\(%s\))*" % P2
POOL = r"((?:this\.)?\w+(?:\.\w+)*(?:\(\))?)\s*"


def split_args(s):
    out, depth, cur = [], 0, []
    i = 0
    while i < len(s):
        ch = s[i]
        if ch == '"':
            j = i + 1
            while j < len(s) and s[j] != '"':
                j += 2 if s[j] == "\\" else 1
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
    out = []
    pos = 0
    for m in re.finditer(name_re, text):
        if m.start() < pos:
            continue
        open_idx = m.end() - 1
        end = call_span(text, open_idx)
        rep = fn(m, split_args(text[open_idx + 1:end - 1]))
        if rep is None:
            continue
        out.append(text[pos:m.start()])
        out.append(rep)
        pos = end
    out.append(text[pos:])
    return "".join(out)


def utf8_string(expr, pool):
    """The String a `pool.utf8Entry(s)` expression mints, else None."""
    m = re.fullmatch(re.escape(pool) + r"\.utf8Entry\((%s)\)" % P3, expr.strip(), re.S)
    return m.group(1).strip() if m and len(split_args(m.group(1))) == 1 else None


def migrate(text):
    text = rewrite_calls(text, POOL + r"\.addUtf8\(",
                         lambda m, a: "%s.utf8Entry(%s)" % (m.group(1), a[0]) if len(a) == 1 else None)
    for _ in range(4):
        def nat_fn(m, a):
            return "%s.entries().nameAndTypeEntry(%s, %s)" % (m.group(1), a[0], a[1]) if len(a) == 2 else None
        text = rewrite_calls(text, POOL + r"\.addNameAndType\(", nat_fn)

        def ref_fn(kind, entry):
            def fn(m, a):
                pool = m.group(1)
                if len(a) != 2:
                    return None
                owner, nat = a
                mm = re.fullmatch(re.escape(pool) + r"\.entries\(\)\.nameAndTypeEntry\((.*)\)", nat.strip(), re.S)
                if not mm:
                    return "%s.entries().%s(%s, %s)" % (pool, entry, owner, nat)
                n, d = split_args(mm.group(1))
                ns, ds = utf8_string(n, pool), utf8_string(d, pool)
                if ns is not None and ds is not None:
                    return "%s.%s(%s, %s, %s)" % (pool, kind, owner, ns, ds)
                return "%s.%s(%s, %s, %s)" % (pool, kind, owner, n, d)
            return fn
        text = rewrite_calls(text, POOL + r"\.addMethodref\(", ref_fn("methodRef", "methodRefEntry"))
        text = rewrite_calls(text, POOL + r"\.addInterfaceMethodref\(",
                             ref_fn("interfaceMethodRef", "interfaceMethodRefEntry"))
        text = rewrite_calls(text, POOL + r"\.addFieldref\(", ref_fn("fieldRef", "fieldRefEntry"))

        def one_fn(kind):
            def fn(m, a):
                if len(a) != 1:
                    return None
                s = utf8_string(a[0], m.group(1))
                return "%s.%s(%s)" % (m.group(1), kind, s if s is not None else a[0])
            return fn
        text = rewrite_calls(text, POOL + r"\.addClass\(", one_fn("classEntry"))
        text = rewrite_calls(text, POOL + r"\.addString\(", one_fn("stringEntry"))
    text = re.sub(POOL + r"\.addInteger\(", r"\1.entries().intEntry(", text)
    text = re.sub(POOL + r"\.addLong\(", r"\1.entries().longEntry(", text)
    text = re.sub(POOL + r"\.addDouble\(", r"\1.entries().doubleEntry(", text)
    text = re.sub(r"(\w+)::addUtf8\b", r"\1::utf8Entry", text)
    for wrapper, entry in TYPES:
        text = re.sub(r"\b(?:am\.ik\.jvm\.)?(?:ConstantPool\.)?%s\b" % wrapper, entry, text)
        text = re.sub(r"\b(?:am\.ik\.jvm\.)?ConstantPool\.(@[\w.]*Nullable) %s\b" % entry, r"\1 " + entry, text)
    return text


TYPES = [("Utf8Constant", "Utf8Entry"), ("ClassConstant", "ClassEntry"),
         ("NameAndTypeConstant", "NameAndTypeEntry"), ("FieldrefConstant", "FieldRefEntry"),
         ("MethodrefConstant", "MethodRefEntry"), ("StringConstant", "StringEntry"),
         ("IntegerConstant", "IntegerEntry"), ("LongConstant", "LongEntry"), ("DoubleConstant", "DoubleEntry")]
ENTRY_TYPES = [e for _, e in TYPES] + ["MemberRefEntry", "InterfaceMethodRefEntry", "LoadableConstantEntry",
                                       "PoolEntry"]


def fix_imports(text):
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
        m = re.fullmatch(r"import ([\w.]+)\.(\w+);", l)
        if i <= last and m and not l.startswith("import static"):
            if m.group(1) == "java.lang.classfile.constantpool" and m.group(2) in ENTRY_TYPES:
                continue
            if m.group(1) in ("am.ik.jvm.ConstantPool",) and not used(m.group(2)):
                continue
            if m.group(1) + "." + m.group(2) in ("am.ik.jvm.ConstantPool.%s" % w for w, _ in TYPES):
                continue
        out.append(l)
    lines = out
    wanted = ["import java.lang.classfile.constantpool.%s;" % t for t in ENTRY_TYPES if used(t)]
    for w in wanted:
        if w in lines:
            continue
        group = [i for i, l in enumerate(lines) if l.startswith("import java.") and not l.startswith("import static")]
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


def paths(argv):
    """The files named, an @FILE naming one per line."""
    for arg in argv:
        if arg.startswith("@"):
            yield from (line.strip() for line in open(arg[1:]) if line.strip())
        else:
            yield arg


if __name__ == "__main__":
    for path in paths(sys.argv[1:]):
        text = open(path, encoding="utf-8").read()
        new = fix_imports(migrate(text))
        if new != text:
            open(path, "w", encoding="utf-8").write(new)
