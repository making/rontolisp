# k00: what was measured (2026-11-18)

Results are in `.kb/java-frontend.md`, "Threads" and "Resumable bodies", and
`.kb/async-await.md`, "Preview-1 wasm-GC, a module naming `rontolisp::%future-new`".

- `sixteen/` -- the sixteen small thread programs of j76's table, written again (the
  first set was not kept). Each under a JDK 25 (`java Program.java`) and as
  `rontolisp Program.java -o p.wasm [--component]` under `wasmtime run`: the same
  output on both targets for all sixteen (`Uncaught` ends with what a thread left
  uncaught on standard error).
- `probes/` -- what the corpus case `threads-wait-for-one-another-again-and-again` was
  made from, and what it does not hold: `Stuck` (two threads each holding the monitor
  the other enters: refused by name, status 1), `Init` (a class whose initialization
  sleeps while a second thread asks for it: a JDK prints `first` and `second` in either
  order), `Exits` (`System.exit` on a started thread), `Plain` (a program that sleeps,
  waits and interrupts itself and starts no thread), `Texts` (a `synchronized
  toString()` under `String.format` in a program that sleeps holding that monitor),
  `Held` (the first form of the corpus case).
- `bench/` -- `Counter` (four threads, 8,000,000 synchronized increments: 0.95 s on one
  stack, 2.45 s resumable) and `Bounce` (400,000 hand-overs by `wait` and `notify`:
  0.9 s; a JDK 4.9 s). `wasmtime compile` first, then `wasmtime run --allow-precompiled`.
- `async/` -- Common Lisp over the state machines: `ping-pong.lisp` (two async bodies
  and a run queue of futures the program settles: the scheduler's shape; the same output
  on Preview 1 and the component), `resumable-machine.lisp` (20,000,000 awaited calls
  that never suspend: 2.15 s on both targets, 0.38 s as plain calls),
  `nested-parameter.lisp` (the `async-lambda` whose parameter was a free variable of the
  body around it).
- `census/Probe.java` -- prints what `JavaResumable` finds of a program: the functions,
  the resumable ones and why, the regions held and why; a third argument prints the
  lowered forms. It stands in the front end's package to reach it:
  `javac -cp target/classes -d out Probe.java`, then
  `java -cp out:target/classes am.ik.rontolisp.java.Probe Program.java [-v [-v]]`.
