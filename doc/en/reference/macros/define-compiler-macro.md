# define-compiler-macro

`(define-compiler-macro name lambda-list body...)`

Defines a compiler macro for `name`: every later call to that function is rewritten by running `body` over the unevaluated argument forms, on all four backends. The definition itself is consumed (it produces no code) and the ordinary function definition stays in place for calls the macro declines to rewrite -- and for `apply`/`funcall`, which never consult a compiler macro.

Returning the `&whole` parameter unchanged is the standard way to decline; a `defmacro` of the same name wins over the compiler macro, as in Common Lisp.

A compiler macro is a hint, and Common Lisp lets an implementation ignore one. rontolisp uses that permission in three cases, all silent: the body signals (the call is left alone), `name` is a standard operator (never registered -- the shared expander lowers those before a compiler macro could see them), or the lambda list is one the macro machinery cannot bind.

Limitations: `notinline` is not implemented, so a call declared `notinline` is still rewritten; the rewrite happens at most once per call site; and any output the body produces at expansion time is suppressed, so it stays identical across backends.

```lisp
(defun myinc (x) (+ x 1))
(define-compiler-macro myinc (x) `(+ ,x 100))
(myinc 10) ; => 110
```

```lisp
(defun mydec (x) (- x 1))
(define-compiler-macro mydec (&whole form x) (declare (ignore x)) form) ; declines
(mydec 10) ; => 9
```
