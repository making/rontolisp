# c46. Clojure: a keyword-dispatched multimethod skips the keyword's arity check

Difficulty: Low

`(defmulti area :shape)` then `(area {:shape :sq} 1 2)` answers the method's value here (measured 2026-10-03,
interpreter); the oracle (`clj` 1.12.6) signals `Wrong number of args (3) passed to: :shape`, because the dispatch
fn `:shape` is called with every argument of the multimethod call and a keyword takes one or two.

The dispatch call of `defmulti` does not go through `rontolisp::%clojure-call` (whose keyword and symbol arms now
check the count), so find the multimethod dispatch site in the lowering and give it the same check (also for a
symbol dispatch fn: `clojure.lang.Symbol`). Pin on all four backends in `clojure-spec.yaml`.
