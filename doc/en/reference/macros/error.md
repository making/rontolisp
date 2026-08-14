# error

`(error datum args...)`

Signals an error, aborting execution unless an enclosing [`handler-case`](handler-case.md) catches it. The Common Lisp condition designators are supported:

- `(error "control" args...)` — the control string uses the same directives as `format` (`~a`, `~s`, `~%`, ...); the remaining arguments fill them to build the message. The control may equally be computed (a variable, a slot read, a function call): a datum that is a string at runtime is a format control and the arguments after it are its format arguments, exactly as for a literal one.
- `(error 'type :initarg value ...)` — constructs a condition instance of the named class (built-in or defined by [`define-condition`](define-condition.md)) and signals it. The message is the class's `:report` rendering -- its own, or the nearest ancestor's when it defines none -- and, for a class with no report anywhere but with the `simple-condition` family's slots, `format` applied to its `:format-control` over its `:format-arguments`. A class that reports nothing at all keeps the `Condition (type initargs...) was signalled.` shape. The message is exactly the text [`princ`](../functions/princ.md) writes for the same condition object (see [`define-condition`](define-condition.md)).
- `(error obj)` — signals a pre-built condition object (e.g. from [`make-condition`](make-condition.md)); a value that turns out to be a string at runtime is signaled as the message it renders (see the first bullet).

Every backend raises the condition so `handler-case` can dispatch on its type: the interpreter and JVM throw an exception carrying the message and the condition object, and the wasm-GC backends throw a WebAssembly exception when the program contains a catching form (`handler-case`/`ignore-errors`/`unwind-protect`; the emitted module then needs `wasmtime -W exceptions=y`) and trap otherwise. `#'error` IS a function value: the interpreter keeps the full designator protocol through it (`(apply #'error (list 'my-error :v x))` builds the typed condition), while the compiled backends forward the datum only -- a symbol datum signals a plain condition naming the class (still caught by `handler-case`'s `error` clause), and trailing initargs/format arguments are dropped.

When nothing catches it, the condition reports itself as **one line on standard error** — `Unhandled condition: ` followed by the same text [`princ`](../functions/princ.md) writes for it — and the process exits non-zero. That line is identical on all four backends; what follows it is only the host's own note that the process died (the JVM's `Exception in thread "main" ...`, wasmtime's trap report). Setting the `RONTOLISP_DEBUG` environment variable to any value additionally prints the JVM stack trace, on the interpreter and on a compiled `.class`. The wasm-GC backends report only when the module carries the exception machinery — that is, when the program contains a catching form somewhere, as any program that loads a library does; without one the module traps with no message, which is what keeps a program that never signals from paying for the machinery. A `--no-wasi` reactor has no standard error to write to at all, so there the report goes into its discarding output sink (see the [wasm-GC module guide](../../guides/wasm-gc-module.md#what-the-build-tells-you-before-you-run-it)).

Because an uncaught `error` aborts execution it is shown here statically rather than as a runnable example:

```console
(error "bad value: ~a" x)
(error 'type-error :datum x :expected-type 'integer)
```
