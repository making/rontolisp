"""opcode_enum.py FILE...: an opcode passed around as an int from am.ik.jvm.Opcode becomes the
java.lang.classfile.Opcode enum it names; prints what it could not decide."""
import re
import sys

NAMES = ["falseBranchOpcode", "exitBranchOpcode", "branchOpcode", "branch", "doubleOpcode", "ifPass",
         "failOpcode", "whenFalse", "whenTrue"]


def fix_imports(text):
    text = text.replace("import am.ik.jvm.Opcode;\n", "")
    if "import java.lang.classfile.Opcode;" in text:
        return text
    lines = text.split("\n")
    java = [i for i, l in enumerate(lines) if l.startswith("import java.")]
    if java:
        pos = java[-1] + 1
        for i in java:
            if lines[i].rstrip(";") > "import java.lang.classfile.Opcode":
                pos = i
                break
        lines.insert(pos, "import java.lang.classfile.Opcode;")
    else:
        first = [i for i, l in enumerate(lines) if l.startswith("import ")][0]
        lines[first:first] = ["import java.lang.classfile.Opcode;", ""]
    return "\n".join(lines)


for path in sys.argv[1:]:
    text = open(path, encoding="utf-8").read()
    for name in NAMES:
        text = re.sub(r"\bint %s\b" % name, "Opcode %s" % name, text)
    text = re.sub(r"private static int branchForMask\(", "private static Opcode branchForMask(", text)
    text = text.replace("default -> -1;\n\t\t};\n\t\treturn branchOpcode >= 0 &&",
                        "default -> null;\n\t\t};\n\t\treturn branchOpcode != null &&")
    text = text.replace("Opcode branchOpcode = switch (head.name()) {", "Opcode branchOpcode = switch (head.name()) {")
    text = fix_imports(text)
    open(path, "w", encoding="utf-8").write(text)
    for i, line in enumerate(text.split("\n"), 1):
        if re.search(r"\bint \w*[Oo]pcode\b", line):
            print("CHECK %s:%d: %s" % (path.rsplit("/", 1)[-1], i, line.strip()))
