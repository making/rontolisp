# k00. Java front end: a thread suspended and resumed on wasm

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; a behaviour is
matched by running a program under a JDK.

State after j89 (2026-11-18, `.kb/java-frontend.md`, "Threads", "A thread suspended and
resumed: the two leads measured", "The regions a suspended thread can hold"): a `.wasm`
runs the threads of a program on one stack. What that cannot do is unchanged: a bounded
buffer, ping-pong, a loop stopped from the thread under it, a second round of one
barrier's threads, a pool thread's `ThreadLocal` over an idle spell, a wait with a limit
ended while a thread runs above it (`invokeAny` with a timeout answers the slow task).
Since j89 a thread can be started for a TIME on one stack (a scheduled pool, a `Timer`),
which needed no suspension.

Two leads, both measured.

- **Stack switching: nothing to do until wasmtime moves.** The project is on 49.0.0 and the
  three conditions all fail there (on by default or settable in the native runner's
  `Config` without losing inlining; aarch64; a collection while a continuation is
  suspended, which aborts the host). Re-measure on every wasmtime the project moves to
  (`.todo/artefacts/j83-java-front-end-what-the-threads-slice-left/stack-switching/`).
  When they hold, the scheduler of `java.lisp`'s one-stack variant switches instead of
  nesting and the lowering does not change.
- **Resumable bodies: j89's analysis says the set is small, so make it.** In six programs
  of seven no `synchronized` region is held across a suspension, and the functions to
  lower twice are the 1.5 to 18% that reach a wait, a sleep or a yield (9 to 55% when
  every monitor's entry counts). The seventh (`Sharing`: `CompletableFuture` beside
  `ConcurrentHashMap.compute` and fork/join) is 5 to 50% between the unsound and the
  sound reading of a call through a function value, and the IR cannot narrow it. Steps:
  1. **The analysis in `JavaLowering`, beside `Effects`**, with what the IR lacks: the
     interface of every lambda and of every call through a function value. Port
     `.todo/artefacts/j89-java-front-end-what-the-concurrent-slice-left/held/held.py`'s
     rules (its header and the kb state them) and measure `Sharing` first: is interface
     precision enough, or are the lambdas handed to each parameter needed (the script's
     `flow` reading gave 34 of 492 on the program as j83 left it)? A test then pins that
     the corpus's thread programs hold no region across a suspension -- which also pins
     the library's rule that a member that never waits calls nothing that can
     (`.kb/java-frontend.md`, "java.util.concurrent").
  2. **What a suspended thread is, per target.** The component has the state machines
     (`WasmAsyncEmit`): a bounded buffer written by hand as `async-defun` bodies, with an
     `unwind-protect` and a `handler-case` across the awaits, prints what the interpreter
     prints (`async/buffer.lisp`); a resumable call that does not suspend adds 90 ns
     to a call there (35 on Preview 1). Missing on BOTH wasm targets: a future the
     program settles, for a ticket a `notify` takes -- a timer cannot stand in
     (`.todo/k02`). Missing on Preview 1: the state machines themselves; an async body
     runs to its end at the call there. So Preview 1 either gets `WasmAsyncEmit`'s frames
     with a scheduler of the module's own, or stays on one stack while the component
     moves -- decide by what the first costs in `WasmFutureRuntimeBuilder`, and say in
     `deviations.md` which target does what.
  3. **The lowering**: a resumable method is an async body and a call of one an `await`
     (await is lexical: a lambda that can suspend is an `async-lambda`, a dispatcher
     reaching a resumable body is resumable); the one-stack variant of `java.lisp`'s
     "threads" becomes a scheduler over suspended bodies -- a started thread a body that
     first awaits its turn, so `start()` returns before the thread runs, as now. Only for
     the wasm lowering: the interpreter and the JVM keep host threads, so the lowering
     takes the target the way `JavaLibrary.forms(oneStack)` does.
  4. **Measure before keeping it**: the sixteen programs of j76's table (all 16 on wasm is
     the goal), the size and time of `Buffer`, `Handing`, `Sharing` and
     `examples/java/shop` against one stack, and that a thread-free program is
     byte-identical.
