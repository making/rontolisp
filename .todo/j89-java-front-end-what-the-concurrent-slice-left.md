# j89. Java front end: what the java.util.concurrent slice (j83) left

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; a behaviour is
matched by running a program under a JDK.

State after j83 (2026-11-17, `.kb/java-frontend.md`, "Threads" and "java.util.concurrent"):
the classes a JDK synchronizes hold their monitor, a class is initialized under its own
state, and locks, latches, queues, `ConcurrentHashMap`, executors and `CompletableFuture`
are Java source over monitors, printing the same on four backends for a program that waits
only for what one stack can run.

- **A thread suspended and resumed on wasm.** Still what one stack cannot do: a bounded
  buffer, ping-pong, a loop stopped from the thread under it, a semaphore held across a
  `yield`, a pool thread's `ThreadLocal` over an idle spell. Both leads were measured
  ("A thread suspended and resumed: the two leads measured"); neither was made.
  - Stack switching: re-measure on every wasmtime the project moves to
    (`.todo/artefacts/j83-java-front-end-what-the-threads-slice-left/stack-switching/`).
    Three conditions, all failing on 49.0.0: on by default or settable in the native
    runner's `Config` without losing inlining; aarch64; a collection while a continuation
    is suspended (aborts the host). When they hold, the scheduler of `java.lisp`'s
    one-stack variant switches instead of nesting and the lowering does not change: the
    work is `cont.new` / `resume` / `suspend` in `am.ik.wasm` and the wasm backend's
    per-thread state (handler stacks, the values channel) swapped at a switch, as
    `WasmFutureRuntimeBuilder.HandlerState` does for an async frame.
  - Resumable bodies: 2 to 16% of a program's functions reach a `wait`, a `sleep` or a
    `yield`, 40 to 47% once a monitor's entry counts (the table in the kb). First step:
    the analysis deciding which `synchronized` regions can be held across a suspension
    (no call in them reaches one), measured on the same five programs; a lowering only if
    that brings the set near the first figure. The component has the state machines
    (`WasmAsyncEmit`), Preview 1 has none.
- **`java.util.concurrent`, not there**: `ReentrantReadWriteLock`, `StampedLock`,
  `CyclicBarrier`, `Phaser`, `Exchanger`, `ScheduledExecutorService`, `ForkJoinPool` (and
  `ForkJoinPool.commonPool()` as a value), `invokeAny`, `CompletableFuture`'s `orTimeout` /
  `completeOnTimeout` / `thenAcceptBoth` / `applyToEither` and the rest, `SynchronousQueue`,
  `PriorityBlockingQueue`, `LinkedBlockingDeque`, `DelayQueue`, `ConcurrentSkipListMap`,
  `ConcurrentLinkedDeque`, `CopyOnWriteArraySet`, the other atomics (`AtomicIntegerArray`,
  `LongAdder`), `new ThreadPoolExecutor(..)` by a program, `toString` of a lock, a latch, a
  pool and a future. `java.util.Timer`.
- **A chain of more than eight keys in a table of 64 or more** is not walked as a JDK walks
  it, in a `ConcurrentHashMap` as in a `HashMap` (`.todo/j87` holds the `HashMap` half).
  Measured: the tenth key goes to the head; what a removal leaves depends on the tree.
- **`volatile` is one lock for every field of the program**: 24 ns a read and write on the
  JVM against javac's 6, and every such access of every thread in turn. A volatile place in
  the JVM backend (a `VarHandle`, or a `volatile` static field for a global) is what removes
  it; the interpreter and wasm need nothing. `Thread` reads a volatile field a start and
  writes one twice a wait and a sleep since j83.
- **A monitor costs 50 to 60 ns on the JVM and 90 ns on wasm** uncontended (javac 19): the
  table's mutex and bookkeeping in a general vector. The table drops monitors past 4,096; a
  weak identity table in the backends retires the bound and the identity hash's retention
  together. Every member of the concurrent classes is a monitor entered: a
  `ConcurrentHashMap.get` pays one.
- **`System.exit` on a started thread, on the interpreter**, ends the program where the
  thread the program started on next parks, or at its end -- not at the call. The compiled
  backends end at the call.
- **Not there**: `ThreadGroup`; `sleep(Duration)` / `join(Duration)` (no `java.time`); a
  JDK's thread numbers; `Thread.State.BLOCKED` (a thread waiting to enter a monitor is
  `RUNNABLE`: the run time's monitor knows, `Thread` does not); the uncaught-exception
  handlers for the thread of `main` (the entry reports it in Lisp, `%java-thread-main`; a
  JDK 25 source launcher prints its trace AND asks the default handler); an atomic is no
  `Number` (`Number` is an intrinsic no source class extends); a class literal is read only
  as a `synchronized` statement's lock.
- **A `Vector` is an `ArrayList` to `instanceof`, a `StringBuffer` a `StringBuilder`**
  (`deviations.md`): a class of its own for each retires it.
- **A thread's uncaught report** is the first line alone; `main`'s has the pipeline's
  location line under it since j82, and holds no condition, which a thread's still does
  (`.todo/j88`).
