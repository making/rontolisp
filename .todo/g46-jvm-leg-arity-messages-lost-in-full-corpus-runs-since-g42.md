# g46: JVM-leg arity/refusal messages lost in full-corpus runs since g42

Difficulty: Hard

Parent: `.todo/g43-protocol-head-vs-oracle-divergences-from-g42.md` (this item
collects what g43's verification proved and deliberately did not touch).

## What diverges

`ClojureSpecE2eTest` (the whole `clojure-spec.yaml` concatenated to one program)
fails 4 cases on the JVM leg only, identically with and without g43's changes:

- `a-wrong-argument-count-is-refused-in-the-oracles-words`
- `a-fn-past-ten-parameters-answers-through-every-call-shape`
- `catch-takes-a-runtime-error-by-the-class-the-oracle-throws`
- `comparator-answers-minus-one-one-or-zero-from-a-predicate`

Symptom: a call with the wrong argument count through a value
(`((fn wna-named [x] x))`, `(let [f wna-va] (f))`, `(apply wna-anon [])`,
`((fn [a] a) 1 2)`, `(wf11 1 2)`, `((comparator false?) 1 2)`) is caught by the
case's `catch clojure.lang.ArityException` but `(.getMessage e)` answers nil,
so the line prints `nil` (or `0`) instead of the `Wrong number of args ...`
words. Direct named calls in the same cases keep their messages. Standalone
small programs (interpreter and JVM) print the messages. The interpreter, wasm
Preview 1 and component legs of the full corpus are green.

## Provenance (measured 2026-10-22, no fix attempted)

- Green at g41 close (`9cb74d346`) and g40 (`ba1b885da`): full corpus green.
- Broken at g42 (`2ead69c9f`) and at `5ce365bbc`/`49a3544c2`, where the corpus
  cannot even run: the g42 registry call names
  `RONTOLISP::%CLOJURE-COLL-REDUCER-ROW`, which the library splice drops, so
  every leg dies with an unbound-variable / cannot-compile-symbol error.
- With only the g43 registry-keyword change applied (record carries the
  reducing method's keyword, `%clojure-extend-method` dispatches on it -- no
  semantic change), the corpus runs and shows exactly these 4 JVM failures.
  So the 4 failures were introduced by g42 (not by g43, not by the g43 row's
  28 lines -- they persist with the row removed), masked until now by the
  unbound-variable crash.

## Acceptance

- Each case green on the JVM leg of the full corpus again, or the mechanism
  documented as intended with its pin.
- Suspects (not verified): the g42 registry store shape (8-field record call
  per `defprotocol`), the value-arm lambdas, or the walk-runtime emission
  shifting the JVM backend's method splitting (`TOP_CHUNK_LIMIT_BYTES`,
  class-pool splitting) past a cliff where the backend's own count check
  (`%arity-report`) loses its message. Note the corpus is one giant class;
  no small program reproduces it (g43 tried: protocol extends, nil rows,
  removes and computed maps ahead of the arity cases stay green on the JVM).
