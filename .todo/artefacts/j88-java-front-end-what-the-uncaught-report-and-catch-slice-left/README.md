# j88: the measurements behind charAt, the failing initialization and the causes (2026-11-18)

Linux x86-64, GraalVM JDK 25.0.5, wasmtime 49. The numbers are in `.kb/java-frontend.md`,
"Exceptions" and "Threads", and `.kb/string-index-cost.md`. Timings and sizes were taken
with `../j82-java-front-end-what-the-exceptions-slice-left/bench.sh` over its `programs/`
(`CharLoop`, `NoTry`, `OneTry`, `TryLoop`), a jar of the commit before the item beside the
item's.

- `programs/Counter.java`, `CounterTry.java`: a program that starts threads, without and
  with a `catch` of its own. What holding no condition would save: each was lowered with
  `--dump-ir`, the `%java-` definitions and the prelude's taken out so the pipeline splices
  them again, the thread body's `handler-case` taken away by hand, and compiled against a
  `java.lisp` ahead of the jar on the class path (`java -cp patch:<jar>
  am.ik.rontolisp.cli.RontoLispCli`) whose `%java-thread-main` has none.
- `programs/Raised.java`: the run time's exceptions at each place a program sees one,
  against a JDK 25 on four backends (two lines differ: the stated messages).
- `programs/InitJdk.java`, `Api.java`: what a JDK 25 answers for a static initialization
  that throws and for the three error classes; `Init2.java` .. `Init4.java` the same in
  what the subset has, `Init3` in threads, `Init4` the class the program starts at.
- `programs/Cause.java`, `Cause2.java`: an uncaught exception with causes and a suppressed
  one, on a thread and on `main`.
