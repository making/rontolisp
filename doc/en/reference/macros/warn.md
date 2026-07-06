# warn

`(warn control-string args...)`

Prints a `WARNING:` message to the standard error stream and returns `nil`; execution continues. The first argument must be a literal control string using the same directives as `format` (`~a`, `~s`, `~%`, ...); the remaining arguments fill those directives to build the message. The `(warn 'condition-name initargs...)` idiom is also accepted and prints the condition designator into the message. There is no condition system, so no condition object is created and nothing can handle or muffle the warning. Like `error`, `warn` is a macro with no function value, so `#'warn` is unsupported. The message goes to standard error on every backend, including the WASM `--component` output (the WASI 0.3 adapter wires fd 2 to `wasi:cli/stderr`).

Because the message goes to standard error (not standard output) it is shown here statically rather than as a runnable example:

```console
(warn "unexpected value: ~a" x)
```

The call prints `WARNING: unexpected value: 42` (for `x` = 42) to standard error and evaluates to `nil`.
