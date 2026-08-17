# load

`(load filename)`

Reads a file and evaluates every top-level form in it in the global environment, then returns `t`. Definitions such as `defun` and `setq` in the loaded file remain available to subsequent code. A relative `filename` resolves against the directory of the file doing the load (the entry file for a top-level `load`), so a program can be run from any working directory and still find a `(load "sibling.lisp")`. In compiled output the loaded definitions live in the runtime `eval` interpreter's global environment, so they are reached through `eval` (e.g. `(load "lib.lisp")` then `(eval '(square 5))`). Works in all three backends; the WASM `load` reads the file with WASI `path_open`, so the module must be run with a directory granted (e.g. `wasmtime run -W gc --dir . prog.wasm`).

```console
(load "lib.lisp")
(eval '(square 5))
```

After loading a file that defines `square`, the definition is invoked through `eval`. The WASM backend needs `--dir` because it resolves the path against the preopened directories: a relative path against the first one, an absolute path against the preopened directory whose name is its longest prefix.

`load` is deliberately **not** idempotent: loading the same file twice evaluates it twice, matching Common Lisp. For load-once module semantics, see [`require`](require.md) / [`provide`](provide.md).
