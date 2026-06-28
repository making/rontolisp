# error

`(error control-string args...)`

Signals an error and aborts the current evaluation. The first argument must be a literal control string using the same directives as `format` (`~a`, `~s`, `~%`, ...); the remaining arguments fill those directives to build the message. The interpreter and JVM backends throw an exception carrying the formatted message, while the WASM backend traps. Like `format`, `error` is a macro with no function value, so `#'error` is unsupported.

Because `error` aborts execution it is shown here statically rather than as a runnable example:

```console
(error "bad value: ~a" x)
```
