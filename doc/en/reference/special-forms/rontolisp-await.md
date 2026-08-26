# rontolisp:await

`(rontolisp:await value)`

Given a future, suspends the current asynchronous function until the future settles and returns its settled value. Settled futures never suspend, nested futures flatten, and a value that is not a future passes through unchanged — like a JavaScript `await` on a non-promise — so `await` can be applied uniformly to a value that may or may not be a future.

```lisp
(rontolisp:await 42)   ; => 42
```

```lisp
(rontolisp:async-defun inner () 10)
(rontolisp:async-defun outer () (+ (rontolisp:await (inner)) 1))
(rontolisp:await (outer))   ; => 11
```

`await` is a special form, legal only inside [`rontolisp:async-defun`](rontolisp-async-defun.md) / [`rontolisp:async-lambda`](rontolisp-async-lambda.md) bodies and at top level (the top level is implicitly asynchronous). Anywhere else — a plain `defun` or `lambda` body, even one nested inside an asynchronous body — it is an error at compile/definition time:

```console
CL-USER> (defun bad () (rontolisp:await 1))
rontolisp:await is only allowed inside rontolisp:async-defun/async-lambda or at top level
```

## Errors

A future that settled with an error re-signals that condition at the `await` — catch it with `handler-case` around the await:

```lisp
(rontolisp:async-defun failing () (error "boom"))
(handler-case (rontolisp:await (failing))
  (error (e) "caught"))   ; => "caught"
```
