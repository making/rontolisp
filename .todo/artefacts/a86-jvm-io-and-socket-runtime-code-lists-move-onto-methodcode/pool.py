"""pool.py FILE...: the pool wrappers to java.lang.classfile entries (a85's mig.py migrate_pool:
`cp.addClass(cp.addUtf8("x"))` -> `cp.classEntry("x")`, the Methodref/Fieldref/String/Integer/
Long facades, the wrapper types), minted in the same order, and the imports that follow."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                                "a85-jvm-runtime-builders-on-jvmasm-move-onto-methodcode"))
from mig import fix_imports, migrate_pool  # noqa: E402

for path in sys.argv[1:]:
    text = open(path, encoding="utf-8").read()
    open(path, "w", encoding="utf-8").write(fix_imports(migrate_pool(text)))
