# j83. Java front end: what the threads slice (j76) left

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; a behaviour is
matched by running a program under a JDK.

State after j76 (2026-11-17, `.kb/java-frontend.md`, "Threads"): a thread is the host's on the
interpreter and the JVM and runs on the waiting thread's stack on wasm; `synchronized`,
`volatile`, `wait`/`notify`, `Thread`, `ThreadLocal` and the four atomics are in; a wait one
stack cannot end is refused by name with the status 1.

- **A thread suspended and resumed on wasm.** Of the 16 programs j76 measured, one stack
  refuses 2 (a bounded buffer, ping-pong), never ends for 2 (a loop stopped by `interrupt` or
  by a `volatile` flag from the thread under it) and prints staggered sleeps in another order.
  Two leads, to measure before choosing: wasm stack switching once the wasmtime the project
  targets has it (the scheduler in `java.lisp` then switches instead of nesting, the lowering
  unchanged); or lowering the methods that can block -- found from the closed program's call
  graph -- as resumable bodies (the component has `%async-run` frames, `.kb/async-await.md`;
  Preview 1 runs an async body to its end, so it needs its own).
- **`java.util.concurrent` past the atomics**: `ReentrantLock`/`Condition`, `CountDownLatch`,
  `Semaphore`, `ConcurrentHashMap`, the blocking queues, `ExecutorService`/`Future`,
  `CompletableFuture`, `TimeUnit`. Java source over monitors, as the atomics are. On one
  stack a pool's worker that waits for work never returns to the thread that submitted it:
  an executor there has to run a task where its result is awaited.
- **The classes a JDK synchronizes take no lock**: `StringBuffer` (a `StringBuilder`'s
  representation), `Vector`, `Hashtable`, `Collections.synchronized*` (not there). Stated in
  `deviations.md`.
- **`volatile` is one lock for every field of the program**: 24 ns a read and write on the
  JVM against javac's 6, and every such access of every thread in turn. A volatile place in
  the JVM backend (a `VarHandle`, or a `volatile` static field for a global) is what
  removes it; the interpreter and wasm need nothing.
- **A monitor costs 50 to 60 ns on the JVM and 90 ns on wasm** uncontended (javac 19): the table's
  mutex and bookkeeping in a general vector. The table drops monitors past 4,096, so more
  objects than that locked in turn make one each time; a weak identity table in the backends
  retires the bound and the identity hash's retention together.
- **One lock for every class's static initialization** (a JDK's is per class): an initializer
  waiting for a thread that initializes another class never ends. On one stack a thread run
  above an initialization in progress reads the class as initialized.
- **`System.exit` on a started thread, on the interpreter**, ends the program where the
  thread the program started on next parks, or at its end -- not at the call: that thread
  running a loop goes on until then. The compiled backends end at the call.
- **Not there**: `Thread.getState`, `ThreadGroup`, the uncaught-exception handlers,
  `sleep(Duration)`/`join(Duration)`, a JDK's thread numbers, `Thread.Builder`'s factory,
  `InheritableThreadLocal`; an atomic is no `Number`; a class literal is read only as a
  `synchronized` statement's lock (`Counter.class.wait()` is refused).
- **A thread's uncaught report** is the first line alone; `main`'s has the pipeline's location
  line under it since j82, and holds no condition, which a thread's still does (`.todo/j88`).
