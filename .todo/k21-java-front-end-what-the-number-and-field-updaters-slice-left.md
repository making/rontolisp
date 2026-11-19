# k21. Java front end: what the Number and field updaters slice (k08) left

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; a behaviour is
matched by running a program under a JDK.

State after k08 (2026-11-19, `.kb/java-frontend.md`, "The decisions", "java.util.concurrent"):
a class of Java source stands under `Number` (the atomics, the adders, the accumulators, a
program's own); the three field updaters; `ExecutorService.invokeAll` with a limit;
`Thread.join(Duration)`. The probes and the script that runs one on four backends:
`.todo/artefacts/k08-java-front-end-what-the-stamped-lock-phaser-and-pool-hooks-slice-left/`.
What follows is what k01 left that k08 did not take, and what k08 found.

- **`java.util.concurrent`, not there**: `PriorityBlockingQueue` and `DelayQueue` (no
  `PriorityQueue` yet: `.todo/k12`), `ConcurrentSkipListMap` / `Set` (no `TreeMap`), a
  `Timer`'s `schedule` of a `Date` (no `java.util.Date`).
- **What a field updater does not take** (`deviations.md`; `Updaters3.java` of the k08
  probes holds a JDK's answers): a class literal of a primitive or an array type
  (`int.class` is `Must be reference type`, `int[].class` an updater; the parser reads
  no such literal: `.todo/k12`, a `Class` as a value), a name that is no constant, a
  field the calling class cannot read (refused by name where a JDK throws
  `RuntimeException: IllegalAccessException: class Near cannot access a member of class
  Far with modifiers "private volatile"` when the call runs), and `newUpdater` behind a
  static import. A swap of an `Integer` field by two boxes of 1000 succeeds (a box is
  its value), where a JDK's two boxes are two objects.
- **A `StringBuilder` is no `Comparable`** (found by k08's first probe, not stated in
  `deviations.md`): `sb instanceof Comparable` is `false` and `compareTo` is not there;
  a JDK's `StringBuilder` and `StringBuffer` compare to their own class.
  `JavaIntrinsics.LIBRARY.implemented` lists `CharSequence` and `Appendable` for the two.
- **Another intrinsic class a program could extend**: only `Number` is abstract, so only
  it takes a class under it (`JavaClass.intrinsicSuperclass`). `class A extends Integer`
  is `a class extending java.lang.Integer is not supported` where javac says `cannot
  inherit from final Integer`.
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
  JVM against javac's 6, and every such access of every thread in turn; a field updater's
  swap is one such access. A volatile place in the JVM backend (a `VarHandle`, or a
  `volatile` static field for a global) is what removes it; the interpreter and wasm need
  nothing. `Thread` reads a volatile field a start and writes one twice a wait and a sleep.
- **A monitor's cost** is `.todo/k20`; the table drops monitors past 4,096, and a weak
  identity table in the backends retires the bound and the identity hash's retention
  together. Every member of the concurrent classes is a monitor entered: a
  `ConcurrentHashMap.get` pays one, an atomic's `get()` too.
- **`System.exit` on a started thread, on the interpreter**, ends the program where the
  thread the program started on next parks, or at its end -- not at the call. The compiled
  backends end at the call.
- **Not there**: `ThreadGroup`; a JDK's thread numbers; the uncaught-exception handlers
  for the thread of `main` (the entry reports it in Lisp, `%java-thread-main`; a JDK 25
  source launcher prints its trace AND asks the default handler); a class literal is read
  only as a `synchronized` statement's lock and a field updater's class;
  `super.getClass()`, `super.clone()` and the other members of `Object` through `super`
  but `toString`, `equals` and `hashCode`; a field through a qualified `super`
  (`Outer.super.x`).
- **A `Vector` is an `ArrayList` to `instanceof`, a `StringBuffer` a `StringBuilder`**
  (`deviations.md`): a class of its own for each retires it. A lead for `Vector`: an
  `AbstractList` holding an `ArrayList` it hands every member to under its monitor (its
  members are `super.m()` calls today, so the change is the receiver).
- **A thread's uncaught report** has its causes since j88 and no location line; `main`'s
  has the pipeline's location line under it since j82, and holds no condition, which a
  thread's still does: measured at 3.5 to 6% of a program that starts threads and kept
  (`.kb/java-frontend.md`, "Threads").
