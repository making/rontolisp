# REPL

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

The REPL supports line editing, history navigation (up/down keys), and Ctrl-C to
cancel input. Type `(quit)` or Ctrl-D to exit.

Try a quick expression here:

```lisp
(let ((x 10) (y 20)) (+ x y))
```
