# REPL

Run `rontolisp` with no file argument to start an interactive read-eval-print
loop. It reads one expression at a time, evaluates it on the tree-walking
interpreter, and prints the result -- the quickest way to explore the language.

```bash
rontolisp
```

```
> (+ 1 2)
3
> (* 3 (+ 4 5))
27
> (defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
fact
> (fact 10)
3628800
> (quit)
```

Each top-level form is evaluated as soon as it is complete, and its value is
echoed back. Definitions persist across inputs: a `defun`, `defvar`, or `setq`
entered at one prompt is visible at every later one, so you can build up state
incrementally within a session.

The prompt accepts multi-line input -- if an expression has unbalanced
parentheses, the REPL keeps reading until it is closed before evaluating. It also
supports line editing, history navigation with the up/down arrow keys, and Ctrl-C
to cancel the current input. Type `(quit)` or press Ctrl-D to exit.

Try a quick expression here:

```lisp
(let ((x 10) (y 20)) (+ x y)) ; => 30
```
