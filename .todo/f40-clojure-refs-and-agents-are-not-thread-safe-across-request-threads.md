# f40. Clojure refs and agents are not thread-safe across request threads

Difficulty: Medium

A Ring handler runs one thread per request on the interpreter and the JVM
(`.kb/concurrent-served-requests.md`). Atoms are atomic there since 2026-10-11 (the guarded
`%clojure-atom-*` helpers in `clojure.lisp`); refs and agents are not. The STM is a
single-threaded extent (`ClojureStateLowering.dosyncOf`/`alterBuild`/`refSetBuild`): `alter`
reads, applies and writes through `C%STM-CHECK` with no isolation, so two requests altering
one ref in `dosync` lose an update, and `C%STM-PUT` pushes onto the validator alist
`C%STM-VALIDATORS` unguarded (a `ref` made per request can lose another's validator).
`send`/`send-off` apply synchronously with the same unguarded write.

Found while fixing the atom case, not reproduced.

## Plan

1. Reproduce: a burst of requests each `(dosync (alter r inc))` once, and each `(send a inc)`;
   the counts must equal the request count (`ClojureAtomConcurrentRequestsTest`'s shape).
2. Decide the transaction model: a single global transaction lock (the body runs serialized;
   no retry, so a side effect runs once, as now) is the smallest change that gives isolation;
   a per-ref read/write set with commit-time validation and retry is the oracle's model.
   Measure which the corpus needs before picking. Agents: an update under the same guard as
   an atom's (`send` stays synchronous). The validator registry: under a guard, or carried
   in the cell.
3. Pin on all four backends (`clojure-spec.yaml`) and update `doc/*/clojure/deviations.md`
   ("Transactions are single-threaded extents").
