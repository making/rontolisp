# b57. core built-in backlog: the seq/map/HOF spellings a library-using program reaches for

Difficulty: Medium

Probed 2026-10-02 against the CLI (`(println (pr-str (op [1 2 3])))`, one per
run); `unknown name` for all of:

`drop-last` `split-at` `split-with` `take-last` `nthnext` `nthrest` `peek`
`pop` `not-empty` `dedupe` `partition-all` `partition-by` `min-key` `max-key`
`juxt` `fnil` `every-pred` `some-fn` `update-keys` `update-vals` `reduce-kv`
`pmap`

plus `add-watch`/`remove-watch` (refused: `atom watches need a design` --
separate design, only take it if a corpus case needs it).

The corpus hits `drop-last` today; the rest are the same shape and cheap once
the lowering row pattern (value = rest/two-argument lambda, strict collections
only) is repeated. `pmap` may lower to `map` (single-threaded like `pvalues`-adjacent
verbs) -- decide, document, pin.

## Oracle

Per op, compare `pr-str` of the result and the error wording for the
misuse cases against `clj -M -e`. Mind the oracle's lazy-vs-strict answers:
`split-at`/`split-with`/`partition-by` return lazy halves -- here strict lists,
printed identically for finite input; pin the printed form, not the lazy type.

## Acceptation

- Every listed op lowers, runs on all four backends via `clojure-spec.yaml`
cases, and rejects the oracle's arity/type errors with the same message.
