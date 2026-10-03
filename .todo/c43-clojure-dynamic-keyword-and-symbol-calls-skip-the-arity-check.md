# c43. Clojure: a keyword or symbol called through the dispatcher skips the arity check

Difficulty: Low

`(let [f 'a] (f))`, `('a)` and `('a 1 2 3)` answer `nil`, `nil` and `2` here (measured 2026-10-03, interpreter);
the oracle (`clj` 1.12.6) signals `ArityException` (`Wrong number of args (N) passed to: clojure.lang.Symbol`)
for any count but 1 or 2. A literal keyword head already refuses at lower time (`:a takes a collection and an
optional default`), but a keyword or symbol reaching `rontolisp::%clojure-call` (a local, a parameter, a quoted
symbol head, `apply`, a seq worker) reads `(car args)` and `(car (cdr args))` unchecked.

Add the count check to the keyword and symbol arms of `%clojure-call` (wording per the oracle:
`clojure.lang.Keyword` / `clojure.lang.Symbol`), pin on all four backends in `clojure-spec.yaml`.
