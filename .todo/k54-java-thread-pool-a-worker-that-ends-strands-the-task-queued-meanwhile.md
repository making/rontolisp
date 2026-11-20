# k54. Java library: a pool worker that ends strands the task queued meanwhile

Difficulty: Medium

Full test 44 (2026-11-20, develop `17ddb5e21`, `LANG=C.UTF-8`) failed once in
`JavaSpecE2eTest.standalone()` on the interpreter, case
`locks-latches-queues-and-executors-hand-work-between-threads`: the line under
"a pool with no core thread still runs what waits in its queue" printed `false ...` for
`ran.await(60, TimeUnit.SECONDS)`, so one of the two tasks never ran.

Suspected cause, in
`src/main/resources/am/ik/rontolisp/java/library/java/util/concurrent/ThreadPoolExecutor.java`:
`next(Worker)` decides under the pool's monitor that the worker ends (it returns `null`
when `keepAlive == 0` and it is above the core, or after the timed wait), but the worker
leaves `workers` only later, in `ended(...)`, in a second `synchronized` block. Between the
two, `execute` counts the worker as alive (`made == 1`), queues the task and `notify()`s
nobody; then the worker removes itself and no thread is left for the queued task. The same
window exists after the timed wait's `return null`.

Plan:
- First a failing test that reproduces it deterministically or by repetition (extend the
  existing case or a unit-level test; no sleeps).
- Make the end decision and the removal one step under the monitor (e.g. `next` removes the
  worker before it returns `null`), and check the other places that count `workers` (the
  shutdown/termination path in `end()`, `ended(...)` for a thrown task, `getPoolSize`).
- Verify on all four backends (`.kb/running-backends.md`).
