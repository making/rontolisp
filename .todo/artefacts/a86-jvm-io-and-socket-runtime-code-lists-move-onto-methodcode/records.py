"""records.py FILE RECORD: new R(name, desc, maxStack, maxLocals, body[, flags]) ->
new R(name, desc, body[, flags]) -- the declared sizes the writer never read. Evaluation order
(the name and descriptor Utf8s before the body's own entries) is kept. Any other arity is
printed for a hand."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                                "a85-jvm-runtime-builders-on-jvmasm-move-onto-methodcode"))
from mig import rewrite_calls  # noqa: E402

path, rec = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()


def fn(m, args):
    if len(args) == 5:
        return "new %s(%s, %s, %s)" % (rec, args[0], args[1], args[4])
    if len(args) == 6:
        return "new %s(%s, %s, %s, %s)" % (rec, args[0], args[1], args[4], args[5])
    print("SKIP", args)
    return None


open(path, "w", encoding="utf-8").write(rewrite_calls(text, r"new (%s)\(" % rec, fn))
