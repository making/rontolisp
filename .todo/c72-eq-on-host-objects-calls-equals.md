# c72. `eq` on host objects calls `equals`

Difficulty: Medium

On the interpreter and the JVM, `eq`/`eql` of two host objects answers their `equals`, not
identity: `(eq (java:new "java.io.File" "x") (java:new "java.io.File" "x"))` is `T` on both
(measured 2026-10-04). On the JVM the left operand is asked, so a Clojure `proxy` whose
`(equals [o] true)` is `identical?` to `true` and to `1`, and `str`/`println` of it print
`true` (the printer's first arm is `(eq x t)`); the interpreter answers `false` there. The
oracle's `identical?` is `==`. `.kb/eq-numbers.md` says nothing about host objects.
Expected: identity for a host object on both backends (numbers and characters keep their
value rule), with the measurement of what code relied on `equals`.
