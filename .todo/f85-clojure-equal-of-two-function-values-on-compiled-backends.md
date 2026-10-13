# f85. Clojure `=` of two function values is false on the compiled backends

Difficulty: Medium

Found closing `.todo/f75` (2026-10-13): `(= f f)` over the same multimethod (or
any function) answers true on the interpreter and in the oracle (identity, as
`MultiFn` overrides no equality), but false on the JVM and both wasm backends.
`%clojure-equal` takes the `(eq a b)` fast path only when the two reads are
`eq`; two `(function name)` sites evaluate to distinct objects there, and CL
`equal` compares functions by identity too. The f75 spec for
`remove-all-methods` pins the answer behaviorally (calling it dispatches like
the multimethod) instead of by `=`, because of this.

## Plan

1. Decide the equality: identity everywhere (make the compiled backends share
   one function object per `defun`/`defmulti`, or compare function values by
   identity in `%clojure-equal`), or document the deviation.
2. clojure-spec rows with `(= f f)`, `(= f g)` and multimethod identity, four
   backends; oracle-check each against clj 1.12.6.
