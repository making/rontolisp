# getenv

`(getenv name)`

Returns the value of the named environment variable as a string, or `nil` if the variable is unset. Works in all three backends; the WASM backend reads the real host environment in Preview 1 and `wasi:cli/environment@0.3.0` in `--component` mode, so pass `--env`/`-S inherit-env` to wasmtime to make variables visible.

```lisp
(getenv "PATH")
```

The result is whatever the host has assigned to the variable, so it is non-deterministic; `(getenv "DEFINITELY_UNSET")` returns `nil`.
