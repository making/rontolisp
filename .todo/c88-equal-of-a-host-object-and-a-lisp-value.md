# c88. `equal` of a host object and a Lisp value differs between the interpreter and the JVM

Difficulty: Medium

`equal` asks a host object's `equals` when both operands are host objects, on both
backends (`.kb/eq-numbers.md`, "Host objects"). With a Lisp value on the right they part:
for a `java:reify` whose `equals` answers true, `(equal r 1)`, `(equalp r 1)` and
`(equal r "s")` are NIL interpreted (the `LispJavaObject` record's `equals` refuses another
record type) and T compiled (`_equal` hands the raw `Long`/`String` to `equals`); Clojure's
`(= p 1)` is `false` interpreted, `true` compiled and in the oracle (clj 1.12.6, which asks
the left operand). Measured 2026-10-04. Deciding it needs the Lisp value converted as an
`Object` argument is (`JavaInterop.receiverObject` / `convert`) before the interpreter asks
`equals`, and the `equal` hash agreeing with whatever is chosen.
