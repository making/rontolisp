# j76. Java front end: threads and `synchronized`

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; what ships is
written here from the specification; no surface says "Java compatible".

**Outside the first target (j68).** Until this item, `Thread`, `synchronized` (the block
and the modifier), `volatile`, `wait`/`notify` and `java.util.concurrent` are refused by
name with a position -- never read as a single-threaded program.

The obstacle is not the lowering: a thread exists on the interpreter and the JVM only
(`.kb/threads.md`, `.kb/mutexes.md`); on WASM preview 1 and the component no handle can
exist, and the front end's rule is the same behaviour on four backends.

- Decide what a threaded Java program is on wasm, with a measurement of each candidate:
  refused at compile time by name; or run cooperatively on one stack (`.kb/async-await.md`
  is what the backend has for a suspended computation), which gives `join` and
  `wait`/`notify` a meaning but not preemption.
- `synchronized` over an object: every object would carry a monitor, or the lock lives in
  a side table keyed by identity; measure what each costs an object that is never locked
  (the structure a class lowers to has no spare slot today).
- `Thread`, `Runnable`, `start`/`join`/`sleep`/`interrupt`, a thread's uncaught exception;
  then virtual threads, `ExecutorService`, the atomics and the concurrent collections as
  programs ask for them.
- The memory model is the host's on the JVM; say what is and is not promised.
