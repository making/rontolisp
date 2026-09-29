"""Moves a JvmAsm-based runtime builder onto am.ik.jvm.MethodCode -- the mechanical part.

    python3 migrate_asm.py src/main/java/am/ik/rontolisp/codegen/jvm/JvmXxxRuntimeBuilder.java

Rewrites the JvmAsm calls to their MethodCode twins, the pool wrappers built from strings to
java.lang.classfile entries (ConstantPool.classEntry/methodRef/stringEntry) and
`new XMethod(name, desc, maxStack, maxLocals, a.code)` to `new XMethod(name, desc, a)`. Prints
the lines it could not rewrite. Left to do by hand: the imports, the builder's record type, the
boundary where a caller hands in legacy wrappers (`.entry()`), and a wrapper-typed parameter
that is only ever passed to invokestatic (widen it to MemberRefEntry). a84 used it on
JvmHashRuntimeBuilder.
"""
import re
import sys

path = sys.argv[1]
text = open(path, encoding="utf-8").read()

BRANCHES = {
    "IFEQ": "ifeq", "IFNE": "ifne", "IFLT": "iflt", "IFGE": "ifge", "IFGT": "ifgt", "IFLE": "ifle",
    "IF_ICMPEQ": "if_icmpeq", "IF_ICMPNE": "if_icmpne", "IF_ICMPLT": "if_icmplt", "IF_ICMPGE": "if_icmpge",
    "IF_ICMPGT": "if_icmpgt", "IF_ICMPLE": "if_icmple", "IF_ACMPEQ": "if_acmpeq", "IF_ACMPNE": "if_acmpne",
    "IFNULL": "ifnull", "IFNONNULL": "ifnonnull", "GOTO": "goto_",
}

# Pool entry creation.
text = re.sub(r"cp\.addMethodref\(\s*([^,]+?),\s*cp\.addNameAndType\(\s*cp\.addUtf8\(([^()]*(?:\([^()]*\))*[^()]*)\),\s*"
              r"cp\.addUtf8\(([^()]*(?:\([^()]*\)[^()]*)*)\)\)\)",
              lambda m: "cp.methodRef(%s, %s, %s)" % (m.group(1).strip(), m.group(2).strip(), m.group(3).strip()),
              text, flags=re.S)
text = re.sub(r"cp\.addClass\(cp\.addUtf8\(([^()]*(?:\([^()]*\)[^()]*)*)\)\)",
              lambda m: "cp.classEntry(%s)" % m.group(1).strip(), text)
text = re.sub(r"cp\.addString\(([^()]*(?:\([^()]*\)[^()]*)*)\)", lambda m: "cp.stringEntry(%s)" % m.group(1).strip(),
              text)

# Types.
text = re.sub(r"\bClassConstant\b", "ClassEntry", text)
text = re.sub(r"\bMethodrefConstant\b", "MethodRefEntry", text)
text = re.sub(r"\bStringConstant\b", "StringEntry", text)

# Assembler.
text = re.sub(r"\bJvmAsm (\w+) = new JvmAsm\(\);", r"MethodCode \1 = new MethodCode();", text)
text = re.sub(r"\bJvmAsm (\w+)\b", r"MethodCode \1", text)
text = re.sub(r"\bint (\w+) = (\w+)\.label\(\);", r"MethodCode.Label \1 = \2.newLabel();", text)
text = re.sub(r"\b(\w+)\.bind\((\w+)\);", r"\1.labelBinding(\2);", text)
text = re.sub(r"\b(\w+)\.branch\(Opcode\.(\w+), (\w+)\);",
              lambda m: "%s.%s(%s);" % (m.group(1), BRANCHES[m.group(2)], m.group(3)), text)
text = re.sub(r"\b(\w+)\.op\(Opcode\.(\w+)\);", lambda m: "%s.%s();" % (m.group(1), m.group(2).lower()), text)
text = re.sub(r"\b(\w+)\.iconst\(", r"\1.loadConstant(", text)
text = re.sub(r"\b(\w+)\.ldcString\(", r"\1.ldc(", text)
text = re.sub(r"\b(\w+)\.anew\(", r"\1.new_(", text)
text = re.sub(r"\b(\w+)\.aconstNull\(\)", r"\1.aconst_null()", text)
text = re.sub(r"\b(\w+)\.newarrayInt\(\)", r"\1.newarray(TypeKind.INT)", text)
text = re.sub(r"new (\w+Method)\((cp\.addUtf8\(\w+\)), (cp\.addUtf8\(\w+\)),\s*(?:[^,]+|\([^)]*\)[^,]*),\s*"
              r"(?:[^,]+?),\s*(\w+)\.code\)",
              r"new \1(\2, \3, \4)", text, flags=re.S)

open(path, "w", encoding="utf-8").write(text)
for i, line in enumerate(text.splitlines(), 1):
    if "JvmAsm" in line or ".code)" in line or "Opcode." in line or "addNameAndType" in line or "addMethodref" in line:
        print("CHECK %d: %s" % (i, line.strip()))
