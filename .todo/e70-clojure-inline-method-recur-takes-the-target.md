# e70. Clojure: `recur` in an inline protocol method takes the target

Difficulty: Medium

In the oracle a `recur` in a `defrecord`/`deftype`/`reify` method body passes every
parameter but the target, which the method supplies itself. Here the inline method's recur
target counts the target, so oracle-valid code is refused. Measured 2026-10-08 (clj 1.12.6):

```clojure
(defprotocol P (m [x acc]))
(deftype C [n] P (m [this acc] (if (> acc 3) [n acc] (recur (inc acc)))))
(m (C. 7) 0) ; oracle [7 4]; here: wrong number of arguments passed to recur: expected 2, got 1
```

`reify` alike. An `extend-protocol`/`extend-type` body is a `fn`, whose `recur` passes every
parameter: already right.

The clojure-spec case `recur-to-variadic-method-splits-a-worker` pins the lenient reading
`(recur t (first r) (rest r))` in an inline variadic method; the oracle refuses variadic
protocol methods at definition, so that line changes with the fix.

## Plan

1. A failing clojure-spec line (and a `ClojureLoweringTest` shape) first.
2. The inline arity's `RecurTarget` excludes the first parameter from its count and the
   recur call prepends it (`ClojureProtocolLowering.fieldArityLambda` and the field-less
   path through `ClojureDispatchLowering.methodLambda` that `reify` takes), one target per
   arity of a multi-arity method.
3. `.kb/clojure-frontend.md` "recur"; `doc/*/clojure/reference/recur.md`.
