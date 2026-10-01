# Clojure: enforce the `try` barrier for `recur` in `binding`/`with-open` bodies (b26 follow-up)

Difficulty: Easy (reuse the b26 `tryDepth` barrier in two more lowering methods).

## Gap (verified 2026-10-01, oracle `clj` 1.12.6.1673)

```clojure
(def ^:dynamic *x* 1)
((fn [n] (binding [*x* 2] (if (zero? n) :d (recur (dec n))))) 2) ; oracle: Cannot recur across try
((fn [n] (with-open [s 1] (recur n))) 1) ; oracle: Cannot recur across try
```

Both macros expand with `try`/`finally` on the oracle (`binding` pushes thread
bindings in a `try`, `with-open` closes in a `finally`), so a `recur` in their
bodies is across a `try`. rontolisp lowers them to `let*` (plus
`unwind-protect` for `with-open`) with no barrier, so both lower to a working
self call instead of refusing.

## Design sketch

- Lower the `bindingOf` and `withOpenOf` bodies behind the b26 `tryDepth`
  barrier (the body, not the inits): a `recur` there trips
  `Cannot recur across try`, while a target opened inside still recurs.
- `binding`/`with-open` bodies stay tail position otherwise (they answer the
  form through the `let*`), so only the barrier is new, not a tail change.
- Pin both refusals with the oracle transcripts in `ClojureLoweringTest`; no
  `clojure-spec.yaml` output changes expected.

## Depends on

b26 (recur tail position + `try` barrier). No dependency on b27/b28.
