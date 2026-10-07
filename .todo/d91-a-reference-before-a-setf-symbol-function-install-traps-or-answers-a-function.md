# d91. A call or #'name before (setf (symbol-function 'name)) installs it

Difficulty: Medium

```lisp
(print (handler-case (funcall 'zz-b 1) (undefined-function (c) (cell-error-name c))))
(print (handler-case #'zz-b (undefined-function (c) (cell-error-name c))))
(setf (symbol-function 'zz-b) (lambda (x) (* x 10)))
(print (funcall 'zz-b 1))
```

SBCL and the interpreter print `ZZ-B`, `ZZ-B`, `10`. The JVM prints `ZZ-B`, `#<function ZZ-B>`,
`10`: `#'zz-b` answers a function before anything is installed. P1 and the component trap
(`unreachable`) on the first form; a direct call `(zz-b 1)` and a computed
`(funcall (intern "ZZ-B") 1)` trap the same way there. A name the program never installs
signals `undefined-function` on all four (`.kb/error-handling.md`, "Undefined functions keep
the call-time stub contract").

## Plan

- Four-backend fixture first (the forms above, plus the direct and computed calls).
- Find what a literal `(setf (symbol-function 'name) ...)` registers on each backend
  (`.kb/symbol-runtime-api.md`) and make its lookup miss report the undefined-function the
  dispatchers report, instead of the wasm trap and the JVM's eager function value.
