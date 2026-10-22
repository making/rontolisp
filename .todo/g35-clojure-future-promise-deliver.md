# g35: Clojure `future` / `promise` / `deliver`

Difficulty: Hard

`ClojureLowering.java:5075-5085` refuses `future` ("no thread pool on any
backend") and `promise` ("blocking rendezvous needs a design") by name, and
`ClojureLoweringTest.java:4453-4461` pins those refusals. The presence probe
(`.todo/g15-remaining-unsupported-clojure-core-vars.md`) lists them as gaps,
while the oracle runs both on real threads with blocking `deref` and timed
wait.

This is the largest concurrency gap in the comparison: without futures,
`pmap`/`pvalues` stay sequential (g36) and real-world concurrent programs
(`clojure.java.shell`-style pipelines, STM-flavored tests spelling
`future-call`/`pcalls`) cannot run.

## What to change

- Lower `future`/`future-call`/`promise`/`deliver`/`future?`/`future-done?`/
  `future-cancelled?`/`future-cancel`/`realized?` and timed `deref` to the
  existing host-thread machinery (`rontolisp:make-thread`, mutexes,
  `.kb/threads.md`; the async `rontolisp:await` future, `.kb/async-await.md`)
  on the interpreter and JVM legs, where threads exist.
- A host `Future` is already a `future?` for the predicate family
  (`ClojureInteropTest.java:2279-2301` pins the oracle shape): reuse that
  recognition so a Clojure future answers the same predicates.
- Keep the WASM legs refusing (single-threaded module; the STM/agent guards
  are already no-ops there, `clojure.lisp:13687-13688`): per-backend
  difference, documented where the `future`/`promise` deviation already is.
  `ClojureWitBoundaryTest.java:670-671` pins the established `:async` refusal
  wording; follow it.
- `deref` with timeout already has partial shape (`ClojureHttpClientTest.java:
  100-107` compiles `deref`/`future?` for the fetch client): unify, do not
  fork a second future representation.

## Acceptance

- Presence probe names resolve and run in both positions (functions in head
  position, values as values); the g15 count moves accordingly.
- `clojure-spec.yaml` rows: `(future (do ...))` delivers the value,
  `(deref (promise) timeout default)` answers the default,
  `future-cancel`/`future-done?`/`realized?` match the oracle on interpreter
  and JVM; WASM legs keep a documented refusal pin.
- `ClojureLoweringTest` refusal pins updated to the per-backend shape
  (call-time refusal only where no threads exist, as g30 did for naming).
- `.kb/clojure-frontend.md` (State section), `doc/en+ja/clojure/deviations.md`
  and the `semantics.md` "Not yet" table lose the `future`/`promise`/
  `deliver` rows; en/ja mirrored in the same commit.
- Full suite green (merge `origin/develop` right before the run, one maven
  run per worktree, no `src/` edits during it).

## Non-goals

- Parallel `pmap`/`pvalues` on top of this -- that is g36.
- An agent thread pool -- that is g38. `await`'s marker-action machinery
  (`clojure.lisp:13930-13958`) stays sender-thread until then.
