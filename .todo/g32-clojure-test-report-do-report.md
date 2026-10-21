# clojure.test report/do-report for test.check defspecs and hive-test properties macros

Difficulty: Medium

Cut from g28 (2026-10-21): the unchecked-math wrapping works, and test.check
1.1.3's seeded `quick-check`s print the oracle's lines on the interpreter and
the JVM. But `clojure.test.check.clojure-test` -- and hive-test's
`properties` macros, which emit its `defspec`s -- cannot load: its
`assertions.cljc` calls `clojure.test/do-report`, which this front end does
not implement:

```
error: .../test.check-1.1.3.jar!/clojure/test/check/clojure_test/assertions.cljc:34:5: unknown name: clojure.test/do-report
```

`defspec`'s direct-call path needs no reporting (it answers the `quick-check`
map), but the namespace must resolve `do-report` (and `default-reporter-fn`
needs `report` and `*testing-vars*`) to compile at all.

## What to build

- `do-report` (and what `clojure.test.check.clojure-test` loads beside it:
  `report` at least for the check types, `*testing-vars*`) over the existing
  test runtime (`clojure.lisp`, "run-tests summary runner": `do-report` of a
  `:pass`/`:fail`/`:error` map counts and prints like the `is` arms do;
  anything else answers nil), so `defspec` loads and a `defspec` run under
  `run-tests` counts and reports like the oracle's.
- Pin with clojure-spec cases: a `defspec` over a small-valued generator
  passes and a failing one reports, on all four backends (the wasm legs need
  g31's statics first for anything past trivial draws -- note the dependency).
- With it, hive-test 0.5.1's `properties` macros (`defprop-roundtrip`,
  `defprop-idempotent`, ...) expand and their `defspec`s pass on the
  interpreter and the JVM; note that in the "Library probes" row.

## Non-goals

- The full `report` multimethod (custom `:type` methods, `*report-counters*`
  outside `run-tests`); only what the check integration calls.
- hive-test's JVM-only namespaces (threads, nREPL, sandbox classloaders).

## Context

g28 (`.todo/g28-unchecked-math-wrapping.md`): the direct `quick-check` path
needs no reporting and is pinned by clojure-spec
`unchecked-math-wraps-star-and-plus-for-splittable-random`. `do-report` is
not in `ClojureTestLowering.VARS`; `g15` tracks `clojure.core` vars only.
