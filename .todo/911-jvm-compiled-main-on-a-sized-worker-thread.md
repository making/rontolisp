# JVM: run compiled output's `main` on a stack the program chose

Difficulty: Medium

Follow-up of `.todo/899`, which measured the option and did not build it.

## The measurement (2026-09-19, linux-x64, Java 25)

A tail call through a procedure value -- `(define (g self n) (if (= n 0) 'done (self self
(- n 1))))`, two JVM frames per Scheme call (the caller and `_invoke_2`) -- reaches, under
`java Prog`:

| stack | depth |
|---|---|
| default (1 MiB) | 1,844 |
| `-Xss16m` | 17,677 |
| `-Xss256m` | 8,000,000 passes (the JIT's frames take over after ~10k calls) |

The interpreter's own 16 MiB worker (`.kb/interpreter-stack.md`) holds 15,497 of the same
calls. So a compiled program is SHALLOWER than the interpreter until the JIT warms up, and
`.kb/interpreter-stack.md` records the decision that left it that way ("a compiled program's
launcher has the knob"). The wasm backends have no ceiling for these calls any more
(`.kb/wasm-tail-calls.md`); the JVM has no `return_call`, so a sized thread is the one lever
left there.

## The plan

- The emitted `main` (`-o Prog.class`, `-o prog.jar`) starts one platform thread with an
  explicit stack size, runs the program on it and joins, exactly as `RontoLispCli.main`
  does for the interpreter: the throwable the worker died with is rethrown on thread 0 (the
  trace and the exit code stay what they are today), `System.exit` inside the program keeps
  working, the thread is named `main` so `java:` interop and thread listings see nothing new.
  A `Runnable` needs a class: the main class itself can implement it (an `<init>` and a
  `run()` calling the static entry), so no second class file has to travel.
- Size: 16 MiB matches the CLI's worker; larger costs only reserved address space. A knob
  replaces `-Xss`, which no longer reaches the program: a system property
  (`-Drontolisp.stack=<MiB>`) read before the thread is created.
- Not for `--no-main`, `-o app.war` and `rontolisp:jvm-export` handles (no `main`), and NOT
  for a program that references `objc:`/`appkit:`: AppKit needs thread 0, and the compiled
  GUI outputs must stay byte-identical, since a GUI change is verified only by hand on macOS
  (`examples/macos/counter.lisp` on `java -jar`, `-o Counter.class`, `-o counter.jar`;
  CLAUDE.md "After Task Completion").
- Blast radius: every JVM artifact with a `main` grows by the launch code; outputs identical.
  Measure the depth after (expect ~17,700 cold at 16 MiB), the startup cost of the extra
  thread, and record both in `.kb/interpreter-stack.md` ("What this does NOT cover" becomes
  what it covers) and `.kb/jvm-export.md`.
