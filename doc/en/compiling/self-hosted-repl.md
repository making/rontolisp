# Self-Hosted REPL

Because `read-line`, `read-from-string`, `eval` and `print` are available in every backend, a REPL can be written in RontoLisp itself and compiled to a standalone `.class` or `.wasm`:

Example (`repl.lisp`):

```console
(princ "> ")
(setq line (read-line))
(while line
  (print (eval (read-from-string line)))
  (princ "> ")
  (setq line (read-line)))
```

```bash
rontolisp repl.lisp               # interpret
rontolisp repl.lisp -o repl.class
java repl                                                                  # REPL on the JVM
rontolisp repl.lisp -o repl.wasm
wasmtime run repl.wasm                                                     # REPL on WASM
```

The self-hosted REPL parses each input line with the embedded runtime reader
(`read-from-string`), which upcases symbols like Common Lisp (see the
[reader case guide](../guides/reader-case.md)), so `(defun square ...)` echoes
`SQUARE`, the same as the native REPL.

```console
> (defun square (x) (* x x))
SQUARE
> (mapcar #'square '(1 2 3))
(1 4 9)
> ()
NIL
> (- 5)
-5
```

`read-line` returns `nil` only at end of input, so the loop exits on Ctrl-D. Entering `nil` or `()` evaluates to `NIL` and the loop keeps going, because the line is read with `read-line` (which distinguishes end of input from a datum) rather than reusing the read value as the loop's exit sentinel. Each line entered at the prompt is parsed by the runtime reader and evaluated by the embedded `eval` runtime, so the [Compiled `eval` limitations](../guides/eval-limitations.md) and [Compiled `read`/`load` limitations](../guides/read-load-limitations.md) apply.
