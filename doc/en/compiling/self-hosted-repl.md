# Self-Hosted REPL

Because `read`, `eval` and `print` are available in every backend, a REPL can be written in RontoLisp itself and compiled to a standalone `.class` or `.wasm`:

Example (`repl.lisp`):

```console
(princ "> ")
(setq form (read))
(while form
  (print (eval form))
  (princ "> ")
  (setq form (read)))
```

```bash
rontolisp repl.lisp               # interpret
rontolisp repl.lisp -o repl.class
java repl                                                                  # REPL on the JVM
rontolisp repl.lisp -o repl.wasm
wasmtime run -W gc repl.wasm                                               # REPL on WASM
```

The self-hosted REPL reads its input with the embedded runtime reader, which
upcases symbols like Common Lisp (see the [reader case guide](../guides/reader-case.md)),
so `(defun square ...)` echoes `SQUARE`, the same as the native REPL.

```console
> (defun square (x) (* x x))
SQUARE
> (mapcar #'square '(1 2 3))
(1 4 9)
> (- 5)
-5
```

`read` returns `nil` at EOF, so the loop exits on Ctrl-D (entering `nil` or `()` also ends it). Each form entered at the prompt is parsed by the runtime reader and evaluated by the embedded `eval` runtime, so the [Compiled `eval` limitations](../guides/eval-limitations.md) and [Compiled `read`/`load` limitations](../guides/read-load-limitations.md) apply.
