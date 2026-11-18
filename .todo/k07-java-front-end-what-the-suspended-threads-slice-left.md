# k07. Java front end: what the suspended threads slice (k00) left

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; a behaviour is
matched by running a program under a JDK.

State after k00 (2026-11-18, `.kb/java-frontend.md`, "Threads" and "Resumable bodies";
`.kb/async-await.md`, "Preview-1 wasm-GC, a module naming `rontolisp::%future-new`"): on
both wasm targets a thread is a body suspended and resumed. `java/JavaResumable` makes the
functions a thread can be suspended in `async-defun`s, `java.lisp`'s wasm variant gives the
turns, the sixteen programs of j76 print a JDK's output, the library has no branch for a
target any more. The probes, the benchmarks and a census of what the analysis finds are in
`.todo/artefacts/k00-java-front-end-a-thread-suspended-and-resumed-on-wasm/`. What is
left, the first two measured:

- **A loop that takes turns is slow.** A synchronized counter under four threads,
  8,000,000 increments: 0.95 s on one stack, 2.45 s resumable. The loop counts its turns,
  so its function is a resume body, and the wasm backend drops the integer fusions, the
  fixnum versions and the counted loops there (`ctx.asyncResume != null` in
  `WasmIntFusionCompiler`, `WasmFixnumVersioning`, `WasmDotimesCompiler`). Two ways, to
  measure before choosing: keep those lowerings in a resume body between two suspend
  sites (the backend), or have `JavaResumable` write a loop whose only suspension is its
  turn as a plain function running up to its 1,024 turns and answering whether it ended
  (its assigned locals become cells).
- **A resume body with an await in most statements is large.** `Handing` is 417 KB on one
  stack and 712 KB resumable, 85 KB of it the one resume body of its `main`; `Sharing`
  676 to 898 KB. `-Drontolisp.wasm.debug-func-sizes` names the functions. What a suspend
  site spills and what the prologue restores is `WasmAsyncEmit`; a function awaited in
  tail position needs no frame of its own beyond what `JavaResumable.forwards` finds
  (a region's body ending in a wait is not a tail).
- **Stack switching, and the component's threads: re-measure on every wasmtime.** 49.0.0
  fails the three conditions for `-W stack-switching` (on by default or settable in the
  native runner's `Config` without losing inlining; aarch64; a collection while a
  continuation is suspended, which aborts the host;
  `.todo/artefacts/j83-java-front-end-what-the-threads-slice-left/stack-switching/`).
  Not measured at all: `-W component-model-threading` (the component model's threads,
  each on a fiber of the host), which the same three conditions decide. When either
  holds the scheduler of `java.lisp` switches instead of settling a future and no
  function is lowered twice.
- **Loops that keep their turn** (`doc/*/java/deviations.md`, "Threads"): one reading a
  plain field, one polling through an interface in its body, one turning while its thread
  holds a monitor, a daemon turning for ever. The rule is `JavaResumable`'s "turn of a
  loop"; widening it to every dispatcher gave every walk of a collection a turn (`Handing`
  153 resumable functions against 113). A do-while's test is lowered into its body and is
  read as a body.
- **What the analysis reads coarsely**: a lambda bound to a local or kept in a field is
  any lambda of its interface where it is called (`Flow.ANY`); a lock is known by its
  static type but for a `final` field named alone and made by `new C()` (a local made so
  is not). A program that sleeps holding a monitor typed `Object` through a local makes
  every entry of the program one that may block.
- **Refused where a JDK goes on**: threads that all wait (a deadlock, a pool never shut
  down, a `Timer` never cancelled: a JDK waits for ever, here the status is 1); a
  `toString()` or `hashCode()` the run time calls that has to wait (`%java-now`); a Java
  file with no `main` loaded by a Common Lisp program that starts a thread.
- **`getState()` never answers `BLOCKED`**: on wasm a thread waiting to enter a monitor is
  now known (the monitor's seventh slot); the host variant still cannot tell.

## Done when

Each item is made, or measured and decided with the numbers in `.kb/java-frontend.md`.
