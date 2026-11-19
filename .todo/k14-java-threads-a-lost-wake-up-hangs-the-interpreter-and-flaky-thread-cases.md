# k14. Java threads: a lost wake-up hangs the interpreter; the thread cases flake under load

Difficulty: High

Moved out of `.todo/k07` (2026-11-19) because it now stops full test runs: full run 19
(`31db65e3a`, 2026-11-19, a todo agent's tests running beside it) never ended --
`JavaSpecE2eTest` produced no report and the run hit its 50-minute limit. k05 saw the same
hang twice in fifteen runs with its changes and once in nine on `23ce3b245`.

## (1) A wake-up that never comes (a bug, not a test problem)

The `java-spec` thread of an INTERPRETER leg stays in `%java-park`'s wait with no limit
(`RontoConditionVariable.await` line 60 under `Environment.registerMutexes`, the `cond` of a
`while`). Seen only under load (load average 9 to 60; not at 4). Which case is not known:
the legs run at once, and a jstack of the fork shows the stack, not the program.

- First find the case: run the standalone interpreter legs alone under load (`stress-ng`
  or a parallel full suite) with each leg's name on its thread, and jstack the fork when it
  stops.
- Then a failing test that reproduces it (a deterministic interleaving if the race can be
  forced, otherwise a loop under load with a bound), then the fix in the park/unpark,
  monitor or condition-variable path. Check the JVM backend's counterpart for the same
  race.

## (2), (3) Orders decided by a short sleep

- `locks-latches-queues-and-executors-hand-work-between-threads`, JDK leg: `pool size = 1`
  where the expectation says 0 (the pool's thread had not left when `toString` ran).
- `threads-wait-for-one-another-again-and-again`, interpreter: `atomic read after some
  turns false` -- `stops()` gives each spinning thread `Thread.sleep(5)` to take a turn.

A latch the thread counts down (or a wait for the state the line prints) is the fix, as
`195dcb88c` did for the pool case of the same program. Sweep the other thread cases of
`java-spec.yaml` (and the Java examples) for an order decided by a sleep.

## Done when

- The hang's case is named, pinned by a test, and fixed; full runs beside a todo agent end.
- No thread case of the corpus depends on a sleep for its order.
