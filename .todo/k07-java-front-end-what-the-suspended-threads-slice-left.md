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
- **A thread waiting for a reply holds the turn** (k03, `.kb/java-frontend.md`,
  "java.net.http"): `%java-suspending-http-reply` gives the threads that can go on one
  turn and then blocks the stack in the host, so a thread asleep does not wake until the
  reply is in, and a reply that waits for what a sleeping thread would send never comes.
  Since k11 a request of the program's own server is a thread too, taken where no
  thread can go on: a `main` asking its own server through the client waits in the host
  and the request is never taken (`doc/*/java/index.md`'s example and
  `examples/java/http/Client.java` run on the host's threads only).
  The scheduler would have to wait for the soonest of its timed threads and the host's
  futures together: a `--native` fetch's future is deferred (no way to ask it without
  waiting: the runner has `rlhttp.head` alone), the component settles a host future only
  while something blocks on its scheduler, and its `%future-race` is `sockets.lisp`'s.
- **A served request that waits holds its instance (moved here by k11, 2026-11-19).**
  Under `wasmtime serve` a request is a thread of the scheduler's, and the scheduler's
  loop for it (`%java-thread-run`, `.kb/java-frontend.md`, "com.sun.net.httpserver")
  runs inside the host's call of `handle`: a sleep passes in the host with the call
  blocked, and a wait that only another request ends is `%java-stuck`, or for ever
  while a timed thread keeps waking. Measured: a request the host hands an instance
  whose handler sleeps 2 s is entered only once that one is answered. A JDK answers
  both at once. What it takes is the item above: the scheduler waiting on the host's
  futures -- its timed threads as timers of the host, `handle` returning with its task
  suspended and the callback driver giving the turns -- and a delayed response that
  ends when the request's thread does, not where the delayed function returns
  (`%http-normalize-streaming`). A `--native` module already takes the next request
  where no thread can go on (`%http-serve-step`), nested in the waiting one's call.
- **`getState()` never answers `BLOCKED`**: on wasm a thread waiting to enter a monitor is
  now known (the monitor's seventh slot); the host variant still cannot tell.
- **The thread cases of `java-spec.yaml` flake when the machine is loaded** (seen
  2026-11-19 by k05, load average 9 to 40 from a full suite in another worktree; not at
  load 4). Three ways, each also on a tree without k05's changes or on the JDK leg:
  (1) `JavaSpecE2eTest` never ends: the `java-spec` thread of an INTERPRETER leg stays in
  `%java-park`'s wait with no limit (`RontoConditionVariable.await` line 60 under
  `Environment.registerMutexes`, the `cond` of a `while`) -- a wake-up that never comes;
  which case was not found (the legs run at once; a jstack of the fork shows the stack,
  not the program). Seen twice in fifteen runs with k05's changes and once in nine on
  `23ce3b245`. **The case is `a-scheduled-pool-and-a-timer-run-tasks-at-their-times`
  (`Timed`), found 2026-11-19 by k11** (start and end markers around
  `standaloneOnInterpreter`; three of four runs of the whole class hung at load 4 to
  10): its `main` waits with no limit in a `get()` or a latch's `await()`, two more
  threads of its pools wait with no limit beside it, and the thread at 100% CPU in the
  dump is another case's, `Turns`' daemon that never ends. It needs no test to show:
  24 processes at once, each running `Timed` four times in one JVM on the interpreter
  (a thread per copy evaluating `SourceLanguage.forFile(..).read(..)`'s forms, 25 s
  each), left 26 of 96 copies waiting on the tree before k11 and 17 of 96 after; six
  such processes one after another left none. (2) `locks-latches-queues-and-executors-hand-work-between-threads` on the
  JDK leg: `pool size = 1` where the expectation says 0 (the pool's thread had not left
  when `toString` ran; seen on both trees). (3) `threads-wait-for-one-another-again-and-again` on the
  interpreter: `atomic read after some turns false` -- `stops()` gives each spinning
  thread `Thread.sleep(5)` to take a turn, which a loaded machine does not grant. (2) and
  (3) are orders decided by a short sleep: a latch the thread counts down is the fix.
  (1) needs the case first: run the standalone interpreter legs alone under
  `stress-ng`-like load with each leg's name on its thread.

## Done when

Each item is made, or measured and decided with the numbers in `.kb/java-frontend.md`.
