# macroexpand-1

`(macroexpand-1 form)`

Expands `form` once when its operator is a user macro (defined with [`defmacro`](../special-forms/defmacro.md)) or a built-in macro, and returns the form unchanged otherwise. Only the top-level operator is expanded; subforms are left alone.

The second value is Common Lisp's `expanded-p` flag: `(multiple-value-list (macroexpand-1 '(unless c x)))` is `((IF C NIL X) T)`. The environment argument is accepted and ignored (there is no lexical macro environment to consult).

On the compilation path only a LITERAL quoted argument expands: the CLI folds the call to its expansion at compile time. A computed argument reaches a compiled program, which has no macro table left, so it answers the form unchanged with `expanded-p` nil — unless the form IS a macro call, which signals `macroexpand-1: a compiled program cannot expand a macro at run time`. That is the same answer [`macro-function`](macro-function.md)'s stub gives there, and it is what makes the usual "expand until it stops expanding" loop terminate on every backend.

```lisp
(macroexpand-1 '(unless c x)) ; => (IF C NIL X)
```

```lisp
(defmacro my-when (test &body body)
  `(if ,test (progn ,@body) nil))
(macroexpand-1 '(my-when (> 2 1) 'a 'b)) ; => (IF (> 2 1) (PROGN 'A 'B) NIL)
```

A non-macro form is returned as-is:

```lisp
(macroexpand-1 '(+ 1 2)) ; => (+ 1 2)
```
