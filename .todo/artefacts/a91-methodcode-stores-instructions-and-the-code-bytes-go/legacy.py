"""legacy.py apply|revert: the temporary patch the records were compared under -- MethodCode's
measure as the code bytes measured (a local in its explicit-slot form, a far branch in its
three-byte short form), so every budget cuts where it did and the writer's layout is the old one.
Byte-identical with it; then revert and measure the change alone. Run from the repository."""
import sys

P = "src/main/java/am/ik/jvm/MethodCode.java"
PAIRS = [
    ("0x3A -> arg <= 3 ? 1 : arg <= 0xFF ? 2 : 4;", "0x3A -> arg <= 0xFF ? 2 : 4; // LEGACY"),
    ("\t\t\tthis.size += op == Opcode.GOTO ? 2 : 5;", "\t\t\tthis.size += 0; // LEGACY"),
]
s = open(P).read()
for new, old in PAIRS:
    a, b = (new, old) if sys.argv[1] == "apply" else (old, new)
    if a not in s:
        sys.exit("not found: " + a)
    s = s.replace(a, b)
open(P, "w").write(s)
print(sys.argv[1], "ok")
