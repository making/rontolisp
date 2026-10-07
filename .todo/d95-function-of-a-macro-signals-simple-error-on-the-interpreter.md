# d95. #'macro and #'special-operator signal simple-error on the interpreter

Difficulty: Low

```lisp
(print (handler-case (funcall 'when t 1) (error (c) (type-of c))))
(print (handler-case (funcall #'while t) (error (c) (type-of c))))
```

SBCL: `UNDEFINED-FUNCTION` (`COMMON-LISP:WHEN is a macro, not a function.`). The JVM, P1 and the
component: `UNDEFINED-FUNCTION` (`The function WHEN is undefined`). The interpreter:
`SIMPLE-ERROR` (`WHEN is a macro or special operator, not a function`), so an
`undefined-function` clause catches it on three backends only. `while` (rontolisp's special
operator, exported from `COMMON-LISP`) is the same case.

## Plan

- Four-backend fixture first.
- Signal `undefined-function` naming the operator from the interpreter's `function` /
  `symbol-function` / designator resolution of a macro or special operator; decide whether the
  text keeps the "macro" wording on every backend.
