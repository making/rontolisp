# load

`(load filename &key verbose print if-does-not-exist external-format)`

Reads a file and evaluates every top-level form in it in the global environment, then returns `t`. `:if-does-not-exist` is real: a false value answers `nil` instead of signalling when the file is not there, which is what makes `(load "optional-config.lisp" :if-does-not-exist nil)` work. The other three are accepted and ignored -- `load` produces no progress output, so `:verbose` and `:print` have nothing to do, and every backend reads UTF-8, so there is no second `:external-format` to select. Every option value is evaluated, in the order it was written, whether or not it is used. Definitions such as `defun` and `setq` in the loaded file remain available to subsequent code. A relative `filename` resolves against the directory of the file doing the load (the entry file for a top-level `load`), so a program can be run from any working directory and still find a `(load "sibling.lisp")`. In compiled output the loaded definitions live in the runtime `eval` interpreter's global environment, so they are reached through `eval` (e.g. `(load "lib.lisp")` then `(eval '(square 5))`). Works in all three backends; the WASM `load` reads the file with WASI `path_open`, so the module must be run with a directory granted (e.g. `wasmtime run --dir . prog.wasm`).

```console
(load "lib.lisp")
(eval '(square 5))
(load "optional.lisp" :if-does-not-exist nil)
```

After loading a file that defines `square`, the definition is invoked through `eval`. The WASM backend needs `--dir` because it resolves the path against the preopened directories: a relative path against the first one, an absolute path against the preopened directory whose name is its longest prefix.

`load` is deliberately **not** idempotent: loading the same file twice evaluates it twice, matching Common Lisp. For load-once module semantics, see [`require`](require.md) / [`provide`](provide.md).
