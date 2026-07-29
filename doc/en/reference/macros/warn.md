# warn

`(warn datum args...)`

Prints a `WARNING:` message to the standard error stream and returns `nil`; execution continues. The same condition designators as [`error`](error.md) are accepted: a literal control string (same directives as `format`), a quoted condition-type symbol with initargs (the class's `:report` -- inherited from an ancestor when the class defines none -- becomes the message, `format` applied to `:format-control`/`:format-arguments` for a `simple-warning` subtype that reports nothing else, and the `Condition (type initargs...) was signalled.` shape when the class reports nothing at all), or a condition object. A [`handler-bind`](handler-bind.md) handler on `warning` runs at the signal point before the message is printed, and can call [`muffle-warning`](../functions/muffle-warning.md) to abort the output (`warn` then returns `nil` silently); `handler-case` catches errors and `signal`, not `warn`. Like `error`, `warn` has a function value: the interpreter keeps the full designator protocol through `#'warn`, the compiled backends forward the datum only. The message goes to standard error on every backend, including the WASM `--component` output (the WASI 0.3 adapter wires fd 2 to `wasi:cli/stderr`).

Because the message goes to standard error (not standard output) it is shown here statically rather than as a runnable example:

```console
(warn "unexpected value: ~a" x)
```

The call prints `WARNING: unexpected value: 42` (for `x` = 42) to standard error and evaluates to `nil`.
