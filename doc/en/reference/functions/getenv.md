# uiop:getenv

`(uiop:getenv name)`

Returns the value of the named environment variable as a string, or `nil` if the variable is unset. Common Lisp has no `getenv`, so this is homed in the `uiop` package -- the portable spelling implementation-independent libraries already use; there is no unqualified `getenv`. Works in all three backends; the WASM backend reads the real host environment in Preview 1 and `wasi:cli/environment@0.3.0` in `--component` mode -- including a `rontolisp:http-handler` component under `wasmtime serve`, which imports the interface for it -- so pass `--env`/`-S inherit-env` to wasmtime to make variables visible.

```lisp
(uiop:getenv "PATH")
```

The result is whatever the host has assigned to the variable, so it is non-deterministic; `(uiop:getenv "DEFINITELY_UNSET")` returns `nil`.
