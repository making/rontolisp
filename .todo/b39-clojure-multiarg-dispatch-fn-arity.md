# Clojure: multi-argument calls to keyword-dispatched multimethods (b36 follow-up)

Difficulty: Small (dispatcher one-liner plus spec pins; four-backend parity by
construction).

## Gap (found implementing b36, oracle `clj` 1.12.6.1673)

The `defmulti` dispatcher applies the dispatch function to every call argument
(`(apply dispatchFn args)`), but a keyword dispatch value lowers to a one-argument
lookup lambda, so any multi-argument call to a keyword-dispatched multimethod
signals `Function expects 1 argument, got 2`:

```clojure
(defmulti w :shape)
(defmethod w :go [m & r] m)
(w {:shape :go} [1]) ; oracle: {:shape :go}; ours: arity signal
```

The oracle applies the keyword to all arguments (`(:shape m extra)` is the lookup
with a default), so extra arguments are accepted. The same shape probably holds
for map/vector/set dispatch values lowered to fixed-arity lookup lambdas. `class`
dispatch is unaffected (the oracle itself refuses a multi-argument `class` call).

## Design sketch

- Teach the fixed-arity collection-dispatch values (`keywordFn` and its map/vector/set
  siblings if they share the shape) to ignore trailing arguments, like the oracle's
  get-with-default, or apply the dispatch function to the argument prefix it names.
- Verify every shape against the host oracle before and after.

## Acceptance

- `clojure-spec.yaml`: multi-argument calls to keyword- (and map/vector/set-)
  dispatched multimethods green on all four backends.
- `.kb/clojure-frontend.md` `defmulti` row updated; doc pages en+ja if the row has
  user-facing surface.

## Depends on

None (dispatcher arity, orthogonal to the b26/b27/b28/b35/b36 recur work).
