# `JvmLispCompilerTest` compiles deep programs on a 1 MiB JUnit worker stack

Difficulty: Low

Seen 2026-09-24 in a full `./mvnw test`:
`JvmLispCompilerTest#compileAndRunABranchSpanningPastTheSigned16BitOffset` failed with
`StackOverflowError` in `CompileTimeBoundp.scan` (recursing at lines 227/228); the same
method passed alone. The class became `@Execution(CONCURRENT)` that morning (a2610fac9),
so its compiles now run on ForkJoin workers with the JVM default stack, where the CLI
compiles on its 16 MiB worker (`RontoLispCli`'s `WORKER_STACK_BYTES`).

`.kb/test-execution.md`, "The in-process interpreter leg runs on the CLI's stack, not
JUnit's", is the same bug for the interpreter leg and its fix: run the body on a thread
with the CLI's stack.

## Plan

- Run the class's compile (or the whole method body) on a sized-stack thread, sharing
  the interpreter leg's helper rather than a second copy.
- Check the other classes that opted into CONCURRENT in a2610fac9 for the same exposure.
