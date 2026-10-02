# b55. clojure.test minimum: `deftest`/`is`/`are`/`testing`/`thrown?` + a summary runner

Difficulty: Medium

22 of the 65 corpus programs are test files; every one stops at
`unknown name: deftest` today. A minimal `clojure.test` surface unlocks the
whole assertion-driven half of the corpus and gives every later gap a natural
test harness.

## Scope

- `deftest` / `use-fixtures`? (defer fixtures unless the corpus needs them) /
`testing` / `is` / `is (thrown? Exc ...)` / `are` templates.
- `deftest` lowers to a zero-arity defun registering the var in a per-namespace
test table; `is` answers `true`/`false`-object and records a failure message
with the form and, on `thrown?`, the caught value.
- A `clojure.test/run-tests` entry (and `run-all-tests`?) that executes the
table and prints the oracle's summary shape (`Ran N tests containing M
assertions. F failures, E errors.`).

## Oracle

Host `clj` 1.12.6.1673. For each behavior, pin against e.g.

```bash
clj -M -e "(require '[clojure.test :as t]) (t/deftest x (t/is (= 1 2))) (t/run-tests)"
```

Failure message wording, `is` truthiness (any non-nil/false passes), `are`
destructuring order and the summary counts must match the oracle's observable
output; the failure report's exact multi-line layout may be pinned loosely
(first line) if the full form needs the printer.

## Acceptance

- The corpus test files lower and their `deftest` bodies run; assertion
outcomes agree with the oracle per case.
- Pinned in `clojure-spec.yaml` where backend-visible, `ClojureLoweringTest`
for shapes/refusals.
