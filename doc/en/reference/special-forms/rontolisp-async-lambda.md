# rontolisp:async-lambda

`(rontolisp:async-lambda (params...) body...)`

The anonymous counterpart of [`rontolisp:async-defun`](rontolisp-async-defun.md): evaluates to a function value whose invocation returns a future. The parameter list supports the same lambda-list keywords as [`lambda`](lambda.md), and the body follows the same semantics as an `async-defun` body — it starts eagerly on invocation, may use [`rontolisp:await`](rontolisp-await.md), and its value (or error) settles the returned future. `(rontolisp:async (lambda ...))` — the [`rontolisp:async`](rontolisp-async.md) wrapper — is an equivalent JavaScript-style spelling.

```lisp
(rontolisp:await (funcall (rontolisp:async-lambda (x) (* x 2)) 21))   ; => 42
```

Being a function value, it can be passed around like any other function; each invocation returns a fresh future:

```lisp
(let ((double-later (rontolisp:async-lambda (x) (* x 2))))
  (rontolisp:futurep (funcall double-later 3)))   ; => t
```

## Backend support

Identical to [`rontolisp:async-defun`](rontolisp-async-defun.md): virtual threads on the interpreter and JVM, the component's asynchronous task under WASM `--component`, immediate completion on Preview 1 WASM, and a compile error under `--no-gc`.
