# k25. java-spec: the timed invokeAll case lets a queued task run under load

Difficulty: Low

## What happened

Full test of 2026-11-19 at `442702ff9`: `JavaSpecE2eTest#standalone()[82]` failed on the JVM
standalone leg. The case is `locks-latches-queues-and-executors-hand-work-between-threads`
in `src/test/resources/java-spec.yaml`, method `limited`, added by k08. The previous full
test, at `823e0058b`, passed with the same case.

```
-  out of time: done=a done/cancelled=!cancelled done/cancelled=!cancelled done/cancelled=!cancelled true started 1 interrupted 1
+  out of time: done=a done/cancelled=!cancelled done/cancelled=!cancelled done=d true started 1 interrupted 1
```

## Cause (read from the case, not yet reproduced)

The case runs a pool of one thread with tasks a, b, c and d, and a limit of 1000 ms. Task b
waits on `gate` until it is interrupted. When the limit passes, `invokeAll` cancels the open
futures in order, as the JDK's `AbstractExecutorService` does. Cancelling b interrupts the
worker, and the worker is then free to take the next queued task. If it takes d before the
caller cancels d, d runs. The JDK has the same race, so the comment's claim that the tasks
behind the held one "never run, whatever the load" does not hold.

## Fix

Make the ordering certain with a latch, not a sleep. In b's `catch (InterruptedException e)`,
wait on a second latch that the caller counts down after `invokeAll` has returned. The
worker then stays in b until every open future is cancelled. The interrupt flag is cleared
when the exception is thrown, so the wait does not throw again. Fix the comment to match.
The expected output does not change: confirm it with `java` (JDK 25) on the case's source.

Write the failing evidence first: reproduce under load, for example with several
`-Dtest=JavaSpecE2eTest` runs alongside a CPU load, or by widening the window, and record
the rate before and after. Then check the other legs (interpreter, wasm, component). They
share the case.
