# error

`(error datum args...)`

Signals an error, aborting execution unless an enclosing [`handler-case`](handler-case.md) catches it. The Common Lisp condition designators are supported:

- `(error "control" args...)` — the control string must be a literal using the same directives as `format` (`~a`, `~s`, `~%`, ...); the remaining arguments fill them to build the message.
- `(error 'type :initarg value ...)` — constructs a condition instance of the named class (built-in or defined by [`define-condition`](define-condition.md)) and signals it. The message is the class's `:report` rendering when one is defined, and the `Condition (type initargs...) was signalled.` shape otherwise; for the `simple-*` classes a supplied `:format-control` becomes the message.
- `(error obj)` — signals a pre-built condition object (e.g. from [`make-condition`](make-condition.md)); a value that turns out to be a string at runtime is signaled as a plain message.

Every backend raises the condition so `handler-case` can dispatch on its type: the interpreter and JVM throw an exception carrying the message and the condition object, and the wasm-GC backends throw a WebAssembly exception when the program contains a catching form (`handler-case`/`ignore-errors`/`unwind-protect`; the emitted module then needs `wasmtime -W exceptions=y`) and trap otherwise. Like `format`, `error` is a macro with no function value, so `#'error` is unsupported.

Because an uncaught `error` aborts execution it is shown here statically rather than as a runnable example:

```console
(error "bad value: ~a" x)
(error 'type-error :datum x :expected-type 'integer)
```
