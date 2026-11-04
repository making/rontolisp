# i42. The handler stacks of a JVM served request and of an async body are shared

Difficulty: Medium

`%handler-clusters%` and `%restart-clusters%` are per thread for a `make-thread` body on the interpreter and the
JVM, and for a served request on the interpreter (`.kb/threads.md`, "What a thread keeps of its own"). Left sharing
the one global, so a thread leaving its `handler-bind` restores what it saved over what another pushed since:

- A served request on the JVM. `JvmHttpHandlerRuntimeBuilder`'s `handle` and the `%http-server-*` seam's handler
  are the entries. `JvmThreadRuntimeBuilder`'s `call()` shows the binding, over `dynVarRuntime.fields()`, but the
  stacks have a `ThreadLocal` only where `JvmLispCompiler` makes every special one (`usesThreads || linkedClass`), so
  a serving program needs them added there too.
- An async body on the interpreter and the JVM. Should a `handler-bind` around the call see a condition the body's
  eager part signals? It does today, by accident of the shared global, and `.kb/async-await.md` should say.

Not measured beyond reading: a burst of requests each with a `handler-bind` and a signalling body should reproduce it
as `ThreadTest#aThreadsHandlersAreItsOwn` does for threads.
