# warn

`(warn datum args...)`

Prints a `WARNING:` message to the standard error stream and returns `nil`; execution continues. The same condition designators as [`error`](error.md) are accepted: a literal control string (same directives as `format`), a quoted condition-type symbol with initargs (a `define-condition` `:report` becomes the message; the `Condition (type initargs...) was signalled.` shape otherwise), or a condition object. Lite: the warning cannot be handled or muffled (no `muffle-warning` restart; `handler-case` catches errors and `signal`, not `warn`). Like `error`, `warn` has a function value: the interpreter keeps the full designator protocol through `#'warn`, the compiled backends forward the datum only. The message goes to standard error on every backend, including the WASM `--component` output (the WASI 0.3 adapter wires fd 2 to `wasi:cli/stderr`).

Because the message goes to standard error (not standard output) it is shown here statically rather than as a runnable example:

```console
(warn "unexpected value: ~a" x)
```

The call prints `WARNING: unexpected value: 42` (for `x` = 42) to standard error and evaluates to `nil`.
