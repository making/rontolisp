# k14. Java threads: a lost wake-up hangs the interpreter; the thread cases flake under load

Difficulty: High

Moved out of `.todo/k07` (2026-11-19) because it now stops full test runs: full run 19
(`31db65e3a`, 2026-11-19, a todo agent's tests running beside it) never ended --
`JavaSpecE2eTest` produced no report and the run hit its 50-minute limit. k05 saw the same
hang twice in fifteen runs with its changes and once in nine on `23ce3b245`.

## (1) A wake-up that never comes (a bug, not a test problem)

The `java-spec` thread of an INTERPRETER leg stays in `%java-park`'s wait with no limit
(`RontoConditionVariable.await` line 60 under `Environment.registerMutexes`, the `cond` of a
`while`). Seen mostly under load (load average 9 to 60), and by k11 on 2026-11-19 in three
of four runs of the whole class at load 4 to 10.

**The case is `a-scheduled-pool-and-a-timer-run-tasks-at-their-times` (`Timed`)**, found by
k11 with a start and an end line printed around `standaloneOnInterpreter`: the one case
that started and never ended. Its `main` waits with no limit (a `get()` or a latch's
`await()`: an `unwind-protect` around a `while` around `Thread.await`), and two virtual
threads of its pools wait with no limit beside it. The thread at 100% CPU in the fork is
not part of it: `Turns`' daemon, which never ends by design.

**It shows outside the test** (`.todo/artefacts/k14-java-threads-a-lost-wake-up-hangs-the-interpreter-and-flaky-thread-cases/`:
`InProc.java` runs each file given on a thread of its own in one JVM on the interpreter and
names the ones still waiting after 25 s; `run.sh` starts 24 of them at once, each on four
copies of `Timed.java`). Measured 2026-11-19 on 64 cores: 26 of 96 copies left waiting on
`1dcaaca01` (before k11), 17 of 96 after it; six such processes one after another, none.

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

- The hang's case (named above) is pinned by a test and fixed; full runs beside a todo
  agent end.
- No thread case of the corpus depends on a sleep for its order.
