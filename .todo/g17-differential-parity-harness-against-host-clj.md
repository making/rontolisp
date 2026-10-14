# g17. Differential parity harness against host `clj` for the builtin surface

Difficulty: Medium

The presence probes behind `.todo/g15`/`.todo/g16` were one-off scripts: name
in, `unknown name` or not. They cannot see the next layer of the coverage gap
-- a builtin that lowers and runs but diverges (wrong arity refusal, different
print, off-by-one `subs`). Today that layer is found only incidentally, by the
library probes in `.kb/clojure-frontend.md` and by bug reports.

The host `clj` (1.12.x, available on this machine) is the oracle and can sit
in the loop: a corpus of `(ns, var, form, expected-shape)` rows, each form run
through both `clj -e` and every rontolisp backend, results compared as
printed values.

## Shape

- Corpus: one small expression per builtin where a canonical expression
  exists (docstring examples of `clojure.core` are the natural source; the
  per-ns families `clojure.string`/`set`/`walk`/`pprint`... included). Pure
  expressions only in the first cut -- no IO, no concurrency, no time-of-day.
- Runner: a test-suite-resident driver (surefire, like the existing E2E
  legs) that skips like `CiSpecE2eTest` does when `clj` is absent, so the
  suite stays green on machines without the oracle.
- Comparison on printed representations, with the known printer deviations
  whitelisted explicitly per row, not globally.
- The report is the deliverable as much as the green run: a per-ns table of
  diverging builtins, from which follow-up `.todo` items are cut.

## Definition of done

- The harness runs the corpus on the interpreter and JVM backend in the
  default suite; the wasm legs behind the existing E2E flags.
- Every divergence it finds on the first full run is either fixed, pinned as
  a deliberate deviation in `.kb/clojure-frontend.md`, or cut as its own
  todo -- none silently ignored.
- Adding a row is one map entry; the failure output names the ns, var, form
  and both outputs.

## Non-goals

- Backend performance, output byte-identity beyond printed values, or
  coverage of `clojure.spec`/`core.async` (not built in here).
- Replacing the existing per-feature pinning tests; this sits above them.
