# i64. A generic on a compiler-lowered built-in answers no call by a run-time name

Difficulty: Medium

`ShadowedBuiltins` renames the dispatcher of a generic whose name a compiler lowers (`%close--dispatch`) and routes the program's
call sites through it, so no registry row of the name reaches it. Measured 2026-11-05 on the JVM, P1 and the component; the
interpreter and SBCL 2.2.9 answer `:CLOSED` for all of them:

```lisp
(defclass c () ())
(defmethod close ((x c) &key abort) (declare (ignore abort)) :closed)
(print (funcall 'close (make-instance 'c)))              ; :CLOSED
(print (funcall (intern "CLOSE") (make-instance 'c)))    ; CLOSE: The value #<C> is not of type STREAM
(print (eval '(close (make-instance 'c))))               ; The function CLOSE is undefined
```

`eval-macros.lisp` works around it for `with-open-file` and `with-open-stream` through `%eval-close`, a compiled call; drop that with
this.

## Plan

Give the generic's name a registry row answering the dispatcher, on both `_lookup` builders (`registryFuncIds` and its WASM twin),
without entering `functions` under that name: the default method's body still has to compile to the built-in. Pin on all four
backends, with a `close` of a stream in the same program.
