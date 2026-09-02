# 514. `LinalgGpuTest` never finishes on an Apple silicon Mac

Difficulty: Medium

Seen 2026-08-25 on this M-series Mac (macOS 26.3, Metal), in a full
`./mvnw spring-javaformat:apply test`: 178 test classes reported, every one of them green
(0 failures, 0 errors) and exactly ONE class started and never reported --
`am.ik.rontolisp.eval.LinalgGpuTest`. The run sat there until it was killed. Reproduced
with the class alone (`-Dtest=LinalgGpuTest`), so it is not a parallel-suite interaction
like `.todo/481`'s drift bound.

What the machine shows while it hangs:

- `sample` of the surefire JVM: no thread on top of stack anywhere in Metal or in the
  binding -- every thread parked in `_pthread_cond_wait` / `semaphore_wait_trap`. Nothing
  is spinning; something is waiting for a completion that never arrives.
- `jstack <pid>` refuses with `state is not ready to participate in attach handshake!`, so
  the Java-level view has to come from `kill -3` into the fork's output file (the dumpstream
  under `target/surefire-reports/`) rather than from an attach.
- The class is `@EnabledIf`-conditional and skips on every CI runner, so CI has never run
  it; only a developer machine with a device does.

Where to start: which test METHOD is in flight when it stops (`kill -3` the fork and read
the dump, or run the methods one at a time), then whether the wait is a Metal command
buffer that never completes (`.kb/gpu.md`'s asynchronous-command-buffer work is
`.todo/495`) or a latch in the test's own harness. Whatever the cause, the class wants a
`@Timeout` so a full `./mvnw test` fails in minutes with a stack instead of hanging.

Not caused by the `objc:`/`appkit:` work of 2026-08-25: `am.ik.gpu`'s `MetalDriver` is its
own hand-written binding and reaches neither `am.ik.objc` nor `appkit.lisp`, and the run
that found this changed only `appkit.lisp`, the native-image metadata, one test table and
documentation.

## Measured 2026-08-29: it is NOT waiting, it is SPINNING, and the method has a name

Seen again in a full `./mvnw test` on the same machine (214 test classes reported, this
one started and never reported). This time `jstack` attached cleanly, which contradicts
the note above -- attach works, at least while the fork is in this state:

```
"ForkJoinPool-61-worker-1" ... cpu=410640.16ms elapsed=416.34s ... RUNNABLE
  at am.ik.rontolisp.macro.LispMacroExpander.normalizeBindingList(LispMacroExpander.java:32549)
  at am.ik.rontolisp.eval.LispEvaluator.evalLet(LispEvaluator.java:7914)
  ...
  at am.ik.rontolisp.eval.LispEvaluator.evalWhile(LispEvaluator.java:8164)
  ...
  at am.ik.rontolisp.eval.LinalgGpuTest.output(LinalgGpuTest.java:913)
  at am.ik.rontolisp.eval.LinalgGpuTest.everyEnumeratedWriterInvalidatesTheResidentCopy(LinalgGpuTest.java:992)
```

Three facts that redirect the investigation:

- **The test thread is `RUNNABLE` and has burned 410 CPU-seconds in 416 wall-seconds** --
  one core pinned at 100%. It is not blocked on a Metal completion; nothing is waiting.
  The earlier `sample` reading ("every thread parked ... something is waiting for a
  completion that never arrives") saw only the `rontolisp-parallel-*` worker pool, which
  is idle by design; the busy thread is the ForkJoin worker running the test.
- **The method is `everyEnumeratedWriterInvalidatesTheResidentCopy`**
  (`LinalgGpuTest.java:992`), through the helper `output` (`:913`).
- **It is stuck inside an interpreted Lisp `while` loop** -- the stack is
  `evalWhile` -> `evalLet` -> ... hundreds of frames of `LispEvaluator`, no Metal frame
  anywhere. So the suspect is the interpreted program that method runs (a loop whose
  termination depends on a value the resident-copy invalidation is supposed to change),
  not the driver.

Start there: read what `output` at `LinalgGpuTest.java:913` evaluates for
`everyEnumeratedWriterInvalidatesTheResidentCopy` and find the `while` whose test never
goes false. The `@Timeout` recommendation above stands regardless -- with it, this would
have failed in minutes with exactly the stack above instead of hanging a full run.

## Measured 2026-09-02: it is SLOWNESS, not a loop -- the class DOES finish

Run alone (`-Dtest=LinalgGpuTest`) on an M4 Max / macOS 26.3.1 it completes in about
**8 minutes**: 40 tests, 2 skipped. So the `while` the note above suspected of never going
false does terminate; what the full-suite observation was seeing is a class that takes
minutes while every sibling takes seconds, and under sixteen-way parallelism takes long
enough to look stopped.

One method alone -- `theResidentTierRunsOverAResidentOperandAndLandsOnTheCpuKernelsBits`,
the one the CPU-pinned fork of a full run was inside -- is **72.7 s**, and it is 72.2 s
with the todo-636 fused tier in and 72.7 s without, so the cost is the test's own shape
rather than any member. The shape: each of these methods evaluates a multi-statement Lisp
program four to eight times through the INTERPRETER at sizes derived from the device's
thresholds, and on Metal `SIDE` is 208 where it is 64 on CUDA, so every program is an
order of magnitude more interpreted work here than the numbers this class was written
against.

What that leaves for this item: the `@Timeout` recommendation above, still worth having,
and the real question -- whether these methods need to run their programs at the FULL
threshold-derived size four to eight times, or whether one legible size and one repeat
would pin the same claims. That is a test-shape change, not a driver bug.

Found on the way (fixed with todo-636 rather than left here):
`theFusedTierReallyRanOnTheDeviceAndAnyOtherAxisDeclines` asserted a residency HIT count
that can only be non-zero where lazy results pay, so it failed on Metal for every build,
before todo-636 and after. It now carries the `lazyResultsOn()` guard its two sibling
tests already carried. Nobody had seen it because this class has never run to completion
on a Mac.

