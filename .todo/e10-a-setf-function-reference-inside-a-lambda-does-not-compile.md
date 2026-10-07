# e10. `#'(setf name)` inside a lambda does not compile on the compiled backends

Difficulty: Low

```lisp
(defun (setf zz-d) (v x) (list v x))
(print (funcall (lambda (a) (funcall #'(setf zz-d) a 2)) 7))
```

SBCL and the interpreter: `(7 2)`. JVM: `Cannot capture variable: ZZ-D`; P1 and component:
`Cannot find variable for closure: ZZ-D`. The same reference at top level or in a `defun` body
compiles, and so does an undefined `(setf name)` there: only the closure's free-variable analysis
reads the `(setf zz-d)` operand of `(function (setf zz-d))` as a variable form. Undefined or defined
makes no difference.

## Plan

- Four-backend fixture first (a `lambda`, a nested `lambda`, `labels`/`flet`-local capture).
- Teach the free-variable collectors (`JvmLambdaCompiler`, the wasm lambda compiler) that the
  operand of `function` naming a `(setf name)` is a function name, not a form.
