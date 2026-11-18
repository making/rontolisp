# k01. Java front end: what the timers and concurrent classes slice (j89) left

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; a behaviour is
matched by running a program under a JDK.

State after j89 (2026-11-18, `.kb/java-frontend.md`, "Threads" and "java.util.concurrent"):
a scheduled pool and a `Timer` run tasks at their times on four backends; a program makes
its own `ThreadPoolExecutor`; read-write locks,
barriers, exchangers, the deques, a `SynchronousQueue`, the atomic arrays, `LongAdder`,
fork/join, `invokeAny`, a future's state and the stages of two futures are there; a
future's dependents run in a JDK's order; a simple name no longer finds a member a class
does not inherit. Since k00 (2026-11-18) a thread is suspended and resumed on wasm
("Resumable bodies"), so the library has no branch for a target; what that left is
`.todo/k07`.

- **`java.util.concurrent`, not there**: `StampedLock`, `Phaser`, `PriorityBlockingQueue`
  and `DelayQueue` (no `PriorityQueue` yet: `.todo/k05`), `ConcurrentSkipListMap` / `Set`
  (no `TreeMap`), `LinkedTransferQueue`, `CountedCompleter`, `LongAccumulator`,
  `DoubleAdder`, the field updaters, `CompletableFuture`'s `exceptionallyAsync`,
  `exceptionallyCompose`, `minimalCompletionStage`, `obtrudeValue`, a `Timer`'s
  `schedule` of a `Date` (no `java.util.Date`), `ThreadPoolExecutor`'s `beforeExecute` /
  `afterExecute` / `terminated` (a hook needs the catch of every exception around a
  task; make it only for a program that overrides one, as `Thread.Guarding` does) and
  its setters but the handler's, `prestartAllCoreThreads`, `purge`.
- **What a pool is not** (`doc/*/java/deviations.md`, "Threads"):
  `Executors.newSingleThreadExecutor()` and `newSingleThreadScheduledExecutor()` answer
  the pool itself where a JDK wraps it (there `instanceof ThreadPoolExecutor` is false);
  a scheduled pool's `getQueue()` is empty; a task the program puts into `getQueue()`
  waits for the next `execute`; a `ForkJoinPool`'s parallelism is a number it answers (2,
  the common pool 1: no `Runtime.availableProcessors`) and no work is stolen; a
  `ForkJoinTask`'s exception is thrown again as it is where a JDK, when another thread
  ran the task, throws a new one of its class holding it (reflection).
- **`Object.toString()` of a nested class's instance** reads `Outer.Inner@1f`; a JDK and
  `getClass().getName()` write `Outer$Inner`. The identity text takes the structure's
  Lisp name (`%java-structure-name`), which is the qualified name. Either the structures
  of nested classes are named by their binary names, or the program tells the run time
  the names that differ.
- **A chain of more than eight keys in a table of 64 or more** is not walked as a JDK walks
  it, in a `ConcurrentHashMap` as in a `HashMap` (the `HashMap` half was decided: not
  derived, `.kb/java-frontend.md`, "The library"). Measured: the tenth key goes to the
  head; what a removal leaves depends on the tree.
- **`volatile` is one lock for every field of the program**: 24 ns a read and write on the
  JVM against javac's 6, and every such access of every thread in turn. A volatile place in
  the JVM backend (a `VarHandle`, or a `volatile` static field for a global) is what removes
  it; the interpreter and wasm need nothing. `Thread` reads a volatile field a start and
  writes one twice a wait and a sleep.
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
  as a `synchronized` statement's lock; `super.getClass()`, `super.clone()` and the other
  members of `Object` through `super` but `toString`, `equals` and `hashCode`.
- **A `Vector` is an `ArrayList` to `instanceof`, a `StringBuffer` a `StringBuilder`**
  (`deviations.md`): a class of its own for each retires it.
- **A thread's uncaught report** has its causes since j88 and no location line; `main`'s
  has the pipeline's location line under it since j82, and holds no condition, which a
  thread's still does: measured at 3.5 to 6% of a program that starts threads and kept
  (`.kb/java-frontend.md`, "Threads").
- **A field hiding one of a superclass is refused by name, a private one included**: a
  private field is no member of the subclass (j89 made a simple name see that), so a
  field of its name hides nothing -- `ScheduledThreadPoolExecutor` could not name a field
  `core`.
