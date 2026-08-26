# REPL

Run `rontolisp` with no file argument to start an interactive read-eval-print
loop. It reads one expression at a time, evaluates it on the tree-walking
interpreter, and prints the result -- the quickest way to explore the language.

```bash
rontolisp
```

```
CL-USER> (+ 1 2)
3
CL-USER> (* 3 (+ 4 5))
27
CL-USER> (defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
FACT
CL-USER> (fact 10)
3628800
CL-USER> (quit)
```

Each top-level form is evaluated as soon as it is complete, and its value is
echoed back. Definitions persist across inputs: a `defun`, `defvar`, or `setq`
entered at one prompt is visible at every later one, so you can build up state
incrementally within a session.

The prompt is the name of the [current package](../reference/packages.md),
the way `CL-USER>` names it in any Common Lisp REPL. It is read again before
every line, so an `(in-package ...)` typed at one prompt shows at the next --
which package a bare symbol is read into is never left invisible:

```console
CL-USER> (defpackage :app (:use :cl))
APP
CL-USER> (in-package :app)
:APP
APP> (defun greet () "hi")
APP::GREET
APP> (in-package :cl-user)
:CL-USER
CL-USER> (app::greet)
"hi"
```

A form that returns [multiple values](../reference/functions/values.md)
echoes every value, one per line -- the quotient and the remainder of `floor`, the
value and the present-p flag of `gethash`. `(values)` returns no value at all and
echoes nothing:

```console
CL-USER> (floor 10 3)
3
1
CL-USER> (gethash 'b (make-hash-table))
NIL
NIL
CL-USER> (values 1 2 3)
1
2
3
CL-USER> (values)
CL-USER>
```

The prompt accepts multi-line input -- if an expression has unbalanced
parentheses, the REPL keeps reading until it is closed before evaluating. It also
supports line editing, history navigation with the up/down arrow keys, and Ctrl-C
to cancel the current input. Type `(quit)` or press Ctrl-D to exit.

Try a quick expression here:

```lisp
(let ((x 10) (y 20)) (+ x y)) ; => 30
```
