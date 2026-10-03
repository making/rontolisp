# c60. Clojure: `catch` and `thrown?` ignore the class

Difficulty: High

`try`'s catch clauses are catch-all in order and `thrown?`/`thrown-with-msg?` match any
condition (`.kb/clojure-frontend.md`, Deviations). Since 2026-10-03 every exception a program
builds is a condition carrying its class name (`C%E-EXCEPTION`, "Exceptions"), so a typed
match is reachable: `(catch IllegalArgumentException e ...)` should take an exception whose
class is that class or a subclass (the hierarchy is known at lowering time through host
reflection for the class names a program can spell), `ExceptionInfo` an ex-info, and a
runtime error should match through a mapping of the CL condition types onto the oracle's
classes (`type-error` -> `ClassCastException`, `division-by-zero` -> `ArithmeticException`,
...), with `Exception`/`Throwable`/`Object` catching everything. Decide the mapping by
measuring the oracle on the corpus's catch sites (shcloj4 `examples.test.interop` asserts
`(thrown? IllegalArgumentException ...)`/`(thrown? ClassCastException ...)`). All four
backends; pin in clojure-spec.
