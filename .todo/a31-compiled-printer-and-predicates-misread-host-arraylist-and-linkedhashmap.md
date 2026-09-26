# Compiled printer and predicates misread a host ArrayList / LinkedHashMap

Difficulty: Medium

The `java:` bridge and the direct sites now tell a host object from a Lisp value
(`.kb/java-interop.md`, "What a host object is"), but the compiled Lisp runtime does not:
a Lisp array is an `ArrayList` and a hash table a `LinkedHashMap`, and the printer and the
type predicates look at the class alone. Measured 2026-09-26, interpreter vs `-o X.class`:

- `(print (java:new "java.util.LinkedHashMap"))` in a program that also calls
  `hash-table-p`: `#<java java.util.LinkedHashMap>` vs `NullPointerException`.
- `(hash-table-p (java:new "java.util.LinkedHashMap"))`: `NIL` vs `T`.
- `(prin1 (java:new "java.util.ArrayList"))` in a program with a `handler-case`:
  `#<java java.util.ArrayList>` vs `IndexOutOfBoundsException` (the plain `print` path
  already checks the header -- `JavaPrint`, `aHostArrayListPrintsOpaquely`); a host list
  holding `1` answers "the value is not of the expected type".
- `vectorp` / `arrayp` / `type-of` on a host `ArrayList`: check each against the interpreter.

Plan:
- One Lisp-array / Lisp-hash-table test for the compiled runtime (the header / `#order`
  tests `_jhost` uses), applied in a `java:` program only (`usesJava`), as `JavaPrint` does,
  so a program without `java:` stays byte-identical.
- Cover every printer entry and the predicates; pin each row on both backends
  (`JavaInteropTest` / `JvmJavaInteropCompilerTest`, a shared `JavaInteropPrograms` text).
