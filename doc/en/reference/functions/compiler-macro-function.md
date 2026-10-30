# compiler-macro-function

`(compiler-macro-function name &optional environment)`

The compiler macro [`define-compiler-macro`](../macros/define-compiler-macro.md) registered for `name`, or `nil` when there is none. The `environment` argument is accepted and ignored.

```lisp
(define-compiler-macro square (&whole form x)
  (if (numberp x) (* x x) form))
(defun square (x) (* x x))
(list (and (compiler-macro-function 'square) t)
      (compiler-macro-function 'car)) ; => (T NIL)
```

On the interpreter the value is the real expander, callable as `(funcall f form environment)`. A compiled program has expanded its compiler macros away, so there the value is the stub [`macro-function`](macro-function.md) answers: exact as a predicate on all four backends, a signal when called. `setf` of it is not supported.
