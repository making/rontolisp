"""unused_imports.py [--fix] FILE...: reports (or removes) imports whose simple name the file never uses."""
import re
import sys

fix = sys.argv[1] == "--fix"
for p in sys.argv[2:] if fix else sys.argv[1:]:
    if not p.endswith(".java"):
        continue
    s = open(p).read()
    lines = s.split("\n")
    body = "\n".join(l for l in lines if not l.startswith("import "))
    out = []
    for l in lines:
        if l.startswith("import ") and not l.startswith("import static"):
            name = l.rstrip(";").split(".")[-1]
            if not re.search(r"\b%s\b" % re.escape(name), body):
                print(p.split("/")[-1], l)
                if fix:
                    continue
        out.append(l)
    if fix:
        n = "\n".join(out)
        # collapse a blank line left doubled by a removed group
        n = re.sub(r"\n\n\n+import", "\n\nimport", n)
        open(p, "w").write(n)
