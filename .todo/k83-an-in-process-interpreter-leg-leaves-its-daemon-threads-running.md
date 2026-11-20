# k83. An in-process interpreter leg leaves its daemon threads running

Difficulty: Medium

Found by k81 (2026-11-21). `java-spec.yaml`'s `threads-wait-for-one-another-again-and-again`
ends `main` with a daemon `while (true) counter.incrementAndGet();` ("a daemon that never
ends does not keep the program"). The JDK, the JVM and wasm legs end it with their process.
The INTERPRETER leg runs in the surefire fork (`JavaSpecE2eTest.standaloneOnInterpreter`), so
the daemon keeps one core busy until the fork exits: measured 100 s of CPU in 103 s of the
thread's life, `AsyncRuntime.spawnThread`'s virtual thread in `evalWhile` /
`evalHandlerCase`. In a full `./mvnw test` that is every test class the fork runs after it;
on CI's runner a core of few. k81 first took it for the cause of its slow test; the cause
was the JVM compile (`.kb/jvm-int-fusion.md`, "Entry points beyond plain trees").

No registry knows an evaluator's threads (`spawnThread` is static, virtual threads are in
no thread group), so the leg cannot stop them. `LispEvaluator.interrupt(Thread)` stops a
thread at its next loop step or call.

To do:

- Decide where a program's end stops its threads in process: an evaluator-owned set of the
  threads it spawned, interrupted when the program exits (`%java-thread-main`'s end,
  `LispExitSignal`), as a process's end stops them. Mind the playground's substitution of
  `AsyncRuntime` and a session (a REPL buffer's daemon must outlive the buffer).
- Pin it: the leg's daemon is gone when the leg returns (a latch or the thread's state, no
  sleep).
