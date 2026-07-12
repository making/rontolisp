# unwind-protect

`(unwind-protect protected cleanup...)`

Evaluates the `protected` form and returns its value, running the `cleanup` forms on **every** exit from it: a normal return, an `error` unwind, and a `return`/`return-from` non-local exit. The cleanup values are discarded. A cleanup form that itself signals replaces the pending exit (the newer error wins, as in Common Lisp).

`unwind-protect` is supported on the **interpreter and the JVM backend**; the WASM compilers reject it (a WASM error is an uncatchable trap, so there is no unwind path on which to run cleanups). The `with-*` macros (`with-open-file`, `with-output-to-string`, `with-input-from-string`, and the `usocket:with-*` family) expand over `unwind-protect` on the interpreter and the JVM, so they release their handle on every exit there; on WASM they keep the close-on-normal-exit shape.

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
