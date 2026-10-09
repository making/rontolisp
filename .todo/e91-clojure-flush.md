# e91. Clojure: `flush`

Difficulty: Low

`(flush)` is `unknown name: flush` (measured 2026-10-09: `(print "a") (flush) (println)`
refuses at line 2). The oracle flushes `*out*` and answers nil; a prompt printed with `print`
needs it. `clojure.main/repl-caught` is written with `(.flush *out*)` in its place (the
stream receiver `.flush` runs on every backend, "Java interop").

## Plan

1. A lowering row (`.kb/adding-primitives.md`): `(flush)` the `.flush` of `*out*`, nil;
   as a value a zero-arity function.
2. clojure-spec case on all four backends; `doc/*/clojure/reference/flush.md`.
3. `clojure.main/repl-caught` back to `(flush)`.
