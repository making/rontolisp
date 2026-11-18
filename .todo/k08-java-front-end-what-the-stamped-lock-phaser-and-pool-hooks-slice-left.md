# k08. Java front end: what the stamped lock, phaser and pool hooks slice (k01) left

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; a behaviour is
matched by running a program under a JDK.

State after k01 (2026-11-18, `.kb/java-frontend.md`, "java.util.concurrent", "The
decisions"): `StampedLock`, `Phaser`, `LinkedTransferQueue`, `CountedCompleter`, the
accumulators and `DoubleAdder` are there; a `ThreadPoolExecutor` has its three hooks, its
setters, `prestart..`, `purge`, on a scheduled pool too; the pools of one thread are behind
wrappers; a `CompletableFuture` has its `exceptionally..` members, `obtrude..`, minimal
stages and `newIncompleteFuture`; a structure is named by its class's binary name; a field
hides one of a class above it and `super.x` reads the one above. The probes and the script
that runs one on four backends:
`.todo/artefacts/k01-java-front-end-what-the-timers-and-concurrent-classes-slice-left/`.

- **`java.util.concurrent`, not there**: `PriorityBlockingQueue` and `DelayQueue` (no
  `PriorityQueue` yet: `.todo/k05`), `ConcurrentSkipListMap` / `Set` (no `TreeMap`), a
  `Timer`'s `schedule` of a `Date` (no `java.util.Date`), `ExecutorService.invokeAll` with
  a limit, the field updaters. A lead for the updaters: `newUpdater(C.class, "f")` with a
  constant name is lowered where it is called to an object holding the field's two
  accessors, as `Pattern.compile` is lowered where it is called; a JDK's refusals
  (`Must be volatile type`, a field that is not there) are then the lowering's.
- **An atomic, an adder and an accumulator are no `Number`** (`deviations.md`): `Number`
  is an intrinsic no source class extends. What it takes: a source class under an
  intrinsic one -- `instanceof Number` gains the predicates of the made classes under
  it, and a member called through `Number` (`intValue()` ..) a dispatcher whose last arm
  is the intrinsic's form -- the mirror of an intrinsic class implementing a source
  interface (`Library.implemented` / `meets`).
- **What a pool is not** (`deviations.md`, "Threads"): a scheduled pool's `getQueue()` is
  empty; a task the program puts into `getQueue()` waits for the next `execute`; a
  `ForkJoinPool`'s parallelism is a number it answers (2, the common pool 1) and no work
  is stolen (`CountedCompleter.helpComplete` does nothing); a `ForkJoinTask`'s exception
  is thrown again as it is where a JDK, when another thread ran the task, throws a new
  one of its class holding it (reflection); `CompletableFuture.defaultExecutor()` answers
  the pool the stages run on, not the object `ForkJoinPool.commonPool()` answers; a
  `LongAccumulator` holds one value where a JDK holds one a thread. A `StampedLock`
  prefers no thread: readers arriving for ever keep a writer out.
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
  JDK 25 source launcher prints its trace AND asks the default handler); a class literal
  is read only as a `synchronized` statement's lock; `super.getClass()`, `super.clone()`
  and the other members of `Object` through `super` but `toString`, `equals` and
  `hashCode`; a field through a qualified `super` (`Outer.super.x`).
- **A `Vector` is an `ArrayList` to `instanceof`, a `StringBuffer` a `StringBuilder`**
  (`deviations.md`): a class of its own for each retires it.
- **A thread's uncaught report** has its causes since j88 and no location line; `main`'s
  has the pipeline's location line under it since j82, and holds no condition, which a
  thread's still does: measured at 3.5 to 6% of a program that starts threads and kept
  (`.kb/java-frontend.md`, "Threads").
- **A corpus line that asks an order of two threads by a short time fails under load**:
  `threads-wait-for-one-another-again-and-again` printed `closed true 1 11` once on the
  interpreter leg while another worktree ran the whole suite (a 10 ms sleep against a
  thread's start; a latch orders the two tasks since `195dcb88c`). The other short
  sleeps of the thread cases were not gone through.
