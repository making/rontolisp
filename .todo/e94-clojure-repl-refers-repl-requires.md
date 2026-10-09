# e94. Clojure: the `clojure>` REPL refers what Clojure's REPL requires

Difficulty: Medium

Clojure's REPL starts with `(apply require clojure.main/repl-requires)`, so `doc`, `pst`,
`pp` and `pprint` (and `source`, `dir`, `apropos`, `find-doc`, `javadoc`, `add-libs`) are
names in `user`. The `clojure>` session refers none: `(doc f)` is `unknown name: doc` until
the input requires `clojure.repl` (2026-10-09, when `clojure.repl` and `clojure.main` were
built in).

## What decides it

- Cost: loading `clojure.repl` (with `clojure.main`) and `clojure.pprint` in every session
  before the first input. Measure the session's startup with and without, and whether the
  refers can load their namespace on first use instead.
- The refused names (`source`, `dir`, `apropos`, `find-doc`, `javadoc`, `add-libs`) should
  name their refusal when called, not be `unknown name`.

## Plan

1. Measure the startup cost; pick eager or first-use loading.
2. `ClojureSession`: the refers; `ClojureSessionTest`, `PlaygroundReplTest`.
3. `doc/*/clojure/` REPL page.
