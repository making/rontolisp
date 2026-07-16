# rontolisp:async

`(rontolisp:async (defun name (params...) body...))` /
`(rontolisp:async (lambda (params...) body...))`

Wraps an ordinary defining form and turns it into its asynchronous counterpart, for a
notation closer to JavaScript's `async function` / `async (...) =>`. Wrapping a
[`defun`](defun.md) is exactly [`rontolisp:async-defun`](rontolisp-async-defun.md), and
wrapping a [`lambda`](lambda.md) is exactly
[`rontolisp:async-lambda`](rontolisp-async-lambda.md) — the wrapper is a pure rewrite, so
the semantics (eager start, futures, [`rontolisp:await`](rontolisp-await.md) placement)
and the backend support are those of the canonical forms.

```lisp
(rontolisp:async (defun add-later (a b)
  (+ a b)))
(rontolisp:await (add-later 20 22))   ; => 42
```

```lisp
(rontolisp:await (funcall (rontolisp:async (lambda (x) (* x 2))) 21))   ; => 42
```

Anything other than a single `defun` or `lambda` form inside the wrapper is an error:

```console
> (rontolisp:async (+ 1 2))
Error: rontolisp:async expects a single (defun ...) or (lambda ...) form to make asynchronous, got: (rontolisp:async (+ 1 2))
```
