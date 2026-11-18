# k02. `(await (wait-for 0))` never yields on the component

Difficulty: Medium

Found by j89 (2026-11-18) while trying a bounded buffer as two async bodies
(`.todo/artefacts/j89-java-front-end-what-the-concurrent-slice-left/async/spin2.lisp`,
wasmtime 49.0.0): a body that polls with `(rontolisp:await (rontolisp:wait-for 0))` in a
loop until another body changes a variable ends on the interpreter and the JVM and never
ends on the component. With `(wait-for 1)` the same program prints the same on all three
(`async/buffer.lisp`). Preview 1 refuses `wait-for` by name.

What happens (read from the behaviour, not yet from the code): on the component a timer of
0 ms is settled when it is made, so the await is of a future the program knows settled and
does not suspend (`.kb/async-await.md`, "Where the eager part ends": a known-settled future
never suspends); the loop never reaches the scheduler and the other body is never resumed.
On the interpreter and the JVM a timer ends the eager part whether it has fired or not
(the 2026-11-07 invariant), so the loop yields.

- Reproduce and add the failing test first: `spin2.lisp`'s shape as a case of the async
  suite on the three suspending backends (`AsyncEvalTest` / `JvmAsyncCompilerTest` /
  `WasmLispCompilerIntegrationTest`, or a ci-spec case skipped on Preview 1).
- Decide which rule holds on all three: a timer always ends the eager part and suspends
  (what the threaded backends do; then the component's `wait-for` of 0 has to answer a
  pending future and go through `_sched_loop` once), or a timer of 0 is settled everywhere
  (then the interpreter and the JVM change, and `aThreadsHandlersAreItsOwn`'s reasoning
  has to be read again). The first keeps "depends on no thread's timing" and gives a
  program a yield; write the decision into `.kb/async-await.md`.
- `.todo/k00` needs a yield and a program-settled future on the component; this item is
  the yield half only as far as `wait-for` goes.
