# unwind-protect

`(unwind-protect protected cleanup...)`

Evaluates the `protected` form and returns its value, running the `cleanup` forms on **every** exit from it: a normal return, an `error` unwind, and a `return`/`return-from` non-local exit. The cleanup values are discarded. A cleanup form that itself signals replaces the pending exit (the newer error wins, as in Common Lisp).

`unwind-protect` is supported on **every backend** except `--no-gc` (a compile error there). On the wasm-GC backends (Preview 1 and `--component`) it compiles through the WebAssembly exception-handling proposal, so running a program that uses it needs wasmtime 37+ with `-W exceptions=y`; a program without catching/cleanup forms is byte-identical to before and keeps its usual command line. Divergence: the cleanups run on a **signaled** error unwind (`error`/`signal`), but a runtime trap (a `(car 5)`-style type failure, integer division by zero) still aborts the instance without running them — the interpreter and the JVM run cleanups for those too. The `with-*` macros (`with-open-file`, `with-output-to-string`, `with-input-from-string`, and the `usocket:with-*` family) expand over `unwind-protect` on every backend, so they release their handle on every exit; on wasm-GC a `with-*` program therefore also compiles in EH mode and needs `-W exceptions=y`.

```lisp
(let ((n 1))
  (list (unwind-protect (+ n 1) (setq n 10)) n)) ; => (2 10)
```

The cleanup also runs when the protected form exits early via `return`:

```lisp
(let ((log nil))
  (dolist (x '(1 2 3))
    (unwind-protect
        (when (= x 2) (return))
      (setq log (cons x log))))
  log) ; => (2 1)
```

Because an uncaught `error` aborts the program, the error path is shown statically:

```console
> (unwind-protect (error "boom") (print :cleaned))
:cleaned
Error: boom
```
