# Clojure: `letfn` + `recur` to `fn`/`defn` (shcloj4 basics)

Difficulty: Medium (lowering + `FreeVarAnalyzer`/tail-call walk, no new runtime;
four-backend parity by construction, but every backend's tail path re-verified).

## Gap (verified 2026-10-01, oracle `clj` 1.12.6.1673)

```clojure
(letfn [(f [x] (if (zero? x) 1 (* x (f (dec x)))))] (f 5))  ; oracle: 120
(let [f (fn f [n acc] (if (zero? n) acc (recur (dec n) (+ acc n))))] (f 5 0)) ; oracle: 15
```

rontolisp today:

- `letfn` -> `error: unknown name: letfn`
- `recur` inside a named `fn` -> `error: recur outside loop`
  (only `loop` sets the recur target; `ClojureLowering.java:966`).

Corpus blocks: `functional.clj` (`tail-fibo`, `recur-fibo` via `letfn` +
`recur`), `primes.clj` (`primes-from` is a named `fn` with `recur`),
`lazy_index_of_any.clj`, `hangman/core.clj:take-guess` (`(recur)` with zero
args targeting its own `defn`).

## Design sketch

- `letfn`: lower to `labels` (same shape as the named-`fn` self-binding the
  `fn` row already uses), sequential specs like `let`, body in tail position
  per binding. Pre-scan registers the names so siblings call each other
  (the `defn` mutual-recursion precedent: `male_female.clj` already works).
- `recur`: track a recur-target stack, not a single loop slot -- `loop`
  pushes, named `fn`/`defn`/`letfn` entries push, plain `lambda` does not.
  Wrong arg count keeps a named refusal (the `defn` wrong-count precedent).
  `recur` with zero args targets a zero-arity `fn` (`take-guess` case).
- Interpreter tail calls already make `labels` self-calls constant-stack
  (`.kb/interpreter-tail-calls.md`); wasm `return_call` likewise
  (`.kb/wasm-tail-calls.md`) -- no backend change expected, but the depth
  case must run on all four (a `recur`-fib sized to overflow a
  non-tail expansion).

## Acceptance

- `clojure-spec.yaml`: `letfn` (single + mutual + closure over outer),
  `recur`-to-named-`fn`, `recur`-to-`defn` (incl. zero-arg), wrong-count
  refusal, `recur`-outside-anything stays a refusal -- identical output on
  all four backends.
- Corpus pins: `functional.clj` fibs (first N equal to oracle),
  `primes.clj` prefix (e.g. first 25 equal to oracle).
- `.kb/clojure-frontend.md`: `letfn` row + `recur` row widened
  (`loop` -> any fn target); doc `clojure/reference` pages en+ja.

## Depends on

b16 (harness/probe pairs). No dependency on b18-b22.
