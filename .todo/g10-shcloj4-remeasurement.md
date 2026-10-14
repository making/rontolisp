# g10: shcloj4 re-measurement: interop counts and the `?.` unblock

Re-measured 2026-10-14 (`target/rontolisp-0.1.0-SNAPSHOT-exec.jar` from this commit,
oracle `clj` 1.12.6.1673, drivers `(require 'ns) (run-tests 'ns)` from `code/`):

- 24/27 `code/test/**` namespaces print byte-identical stdout to the oracle
  (verified by diff; 148 assertions across the green suites).
- `examples.test.functional`: interpreter ends on `stack overflow` (documented
  deviation, JVM green), oracle `Ran 7 tests containing 19 assertions. 0 failures,
  0 errors.`
- `examples.test.interop`: `Ran 6 tests containing 17 assertions. 0 failures,
  1 errors.` -- the `(thrown? ClassCastException (typed-describe-class "foo"))`
  line. The `#^Class` hint stays dropped by design (tracking it would change the
  bytes of every program that hints); since the Refusals change the reflective
  `No matching method` refusal carries `IllegalArgumentException`, the CCE catch
  no longer takes it. This item updates the stale `0 failures, 0 errors.`
  measurement in `.kb/clojure-frontend.md` ("clojure.test", ~line 6896) to the new
  numbers with date. No code change.
- `examples.test.sequences`: blocked on g09; re-run after it lands.
