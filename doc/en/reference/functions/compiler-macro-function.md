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

On the interpreter the value is the real expander, callable as `(funcall f form environment)`. A compiled program has expanded its compiler macros away, so there the value is the stub [`macro-function`](macro-function.md) answers: exact as a predicate on all four backends, a signal when called.

`(setf (compiler-macro-function name) function)` installs `function` as the compiler macro, and `nil` removes it; `compiler-macro-function` then answers that function itself on every backend. The interpreter applies it to the calls it evaluates afterwards. A compiled program's calls were expanded when it was compiled, so only a top-level `setf` of a quoted name with a `lambda` or `#'` value rewrites the calls after it there, as `define-compiler-macro` does.

```lisp
(setf (compiler-macro-function 'twice)
      (lambda (form env) (declare (ignore env)) `(* 2 ,(second form))))
(defun twice (x) (+ x x))
(list (twice 21) (funcall (compiler-macro-function 'twice) '(twice 4) nil)) ; => (42 (* 2 4))
```
