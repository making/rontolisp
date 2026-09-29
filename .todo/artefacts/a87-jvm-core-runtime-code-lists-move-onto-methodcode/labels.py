"""labels.py FILE...: per method, the labels created with newLabel() that are never bound
(no labelBinding(X) in the same method) or bound more than once textually."""
import re
import sys

for path in sys.argv[1:]:
    text = open(path).read()
    for chunk in text.split("\n\t}\n"):
        m = re.search(r"\n\t(?:private |public |protected )?(?:static )?[\w<>\[\]., @]+ (\w+)\(", chunk)
        name = m.group(1) if m else "?"
        made = re.findall(r"MethodCode\.Label (\w+) = \w+\.newLabel\(\)", chunk)
        for label in set(made):
            binds = len(re.findall(r"\.labelBinding\(%s\)" % re.escape(label), chunk))
            uses = len(re.findall(r"\b%s\b" % re.escape(label), chunk))
            if binds != 1:
                print("%s %s: %s created %d, bound %d, used %d" % (path.rsplit('/', 1)[-1], name, label,
                                                                   made.count(label), binds, uses))
