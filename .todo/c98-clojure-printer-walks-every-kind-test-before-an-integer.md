# c98. Clojure printer walks every kind test before an integer

Difficulty: Low

`%clojure-write` (`clojure.lisp`) tests an integer against every clause of its `cond` --
about twenty kind tests, most of them library calls (`%clojure-lazy-p`, `%clojure-keyword-p`,
`%clojure-var-p`, ..., `%clojure-host-seqable-p`, then `%clojure-host-class-name` ->
`%clojure-lisp-value-p`) -- before the fall-through `princ`. The interpreter keeps every arm,
so each is a call there: one more clause (the host-collection arm) made `pr-str` of a
50k-integer vector 6.87 -> 7.40 s / 4 (+8%, 2026-10-04, `.kb/clojure-frontend.md`, "Java
interop", host collections under the printer), i.e. about 35 us per integer, nearly all of it
kind tests.

Plan: measure an early `((integerp x) (princ x stream))` (or `numberp` with the float clauses
moved above it) on the interpreter and the compiled backends, and the size it costs every
printing program (wasm P1, `--optimize=size`, component, JVM class). The answer must stay
identical (ratios and bignums print through `princ` today).
