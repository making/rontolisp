# Compiled accessors misread a host ArrayList / LinkedHashMap

Difficulty: Medium

The printer and the type predicates tell a host collection from a Lisp array / hash table
(`JvmJavaDirectSites.lispArray` / `lispTable`, `.kb/java-interop.md`, "What a host object
is"), but the accessors still read the class alone. Measured 2026-09-26, interpreter vs
`-o X.class`, `l` a host `ArrayList` holding `1`, `m` a host `LinkedHashMap`:

- `(gethash 1 m)`: `GETHASH expects a hash table, got #<java java.util.LinkedHashMap>` vs
  `NIL` -- a silent wrong answer, the one row that is not merely a different error.
- `(hash-table-count m)`, `(maphash f m)`: `... expects a hash table` vs
  `NullPointerException`.
- `(length l)`, `(coerce l 'list)`: `LENGTH: The value #<java java.util.ArrayList> is not of
  type SEQUENCE` vs `ClassCastException` (Integer to Object[]); `(elt l 0)`, `(aref l 0)`:
  `AREF expects an array, got #<java ...>` vs the same `ClassCastException`.
- `(reverse l)` and `(find 1 l)` already agree.

The JVM backend reports any wrong-type accessor argument as a raw Java exception (`(gethash
1 (list 1 2))` is a `ClassCastException`, `(hash-table-count 5)` an
`IncompatibleClassChangeError`), so only the host-collection rows are in scope here.

Plan:
- In a `java:` program only (`ctx.javaSites != null`, as `JvmArraypCompiler` gates), have
  the hash runtime's entries (`_hashGet` / `_hashPut` / `_hashRem` / `_hashClr` /
  `_hashCount` / `_hashValues`) and `_length` / the `aref` path call the shared tests and
  signal the interpreter's text; a program without `java:` stays byte-identical.
- Pin each row on both backends (`JavaInteropTest` / `JvmJavaInteropCompilerTest`, a shared
  `JavaInteropPrograms` text).
