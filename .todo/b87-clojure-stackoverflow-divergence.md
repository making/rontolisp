# b87. `functional` StackOverflowError divergence (pin, no code)

Difficulty: Low

Measured 2026-10-02, oracle `clj` 1.12.6.1673 vs exec jar at `ec836db36`:

- `examples.test.functional`: oracle `Ran 7 tests containing 19 assertions.
  0 failures, 0 errors.` / ronto `error: stack overflow (--stack <MiB>
  raises the limit)`, exit 1, no summary (the first `thrown?` assertion
  aborts the program; the other 6 deftests never run).
- Oracle: `(stack-consuming-fibo 1000000N)` and `(tail-fibo 1000000N)` both
  throw `java.lang.StackOverflowError` (message `nil`), catchable via
  `catch StackOverflowError` / `(is (thrown? StackOverflowError ...))`.
- Ronto: the same `try`/`catch StackOverflowError` and `thrown?` abort with
  the one-line CLI report; the catch never runs. A host `StackOverflowError`
  is a JVM `Error`, not a CL condition (`evalHandlerCase` catches
  `LispEvalException`; JVM landings catch `RuntimeException`;
  `.kb/error-handling.md`); Clojure `try` lowers to `handler-case`
  (table row 75) and `thrown?` matches any *condition*. The CLI converts the
  overflow outside the program (`RontoLispCli.java:1796-1805`).
  `.kb/clojure-frontend.md:571` already names this exact corpus test.
- Everything else in the namespace passes on both: `recur-fibo` / `fibo` /
  `head-fibo` take-10, `faux-curry` -> `2`, all three `count-heads-*` -> `2`
  (`recur` is already constant-stack via `labels` self-call).
- Fixing = synthesizing a catchable depth guard on every call on all four
  backends against JIT-varying frame sizes (depth ceilings are
  JIT-dependent per `interpreter-stack.md:30-43`); explicitly not worth it.

## Plan (no code)

- Record as documented known-divergence (the `.kb` line exists; add the
  corpus-runner skip/expect-fail entry + the user-doc note), pinning:
  `recur-fibo`/`fibo`/`head-fibo`/`faux-curry`/`count-heads-*` oracle-parity
  cases in `clojure-spec.yaml` (all four backends), plus a
  `ClojureLoweringTest` or interop pin that `(thrown? StackOverflowError ...)`
  over a deep non-tail call ends the program with the `stack overflow`
  report instead of running the catch.
- Revisit only if the runtime ever gains catchable depth guards as a
  product feature (not a Clojure item).

## Pin

- `clojure-spec.yaml` passing shapes above (all four backends).
- The overflow divergence pinned by test (not by prose alone).
- E2E: `examples.test.functional` on the expect-fail list with the reason.
