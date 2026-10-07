# d93. An undefined setf function reports its mangled name

Difficulty: Low

```lisp
(print (handler-case (funcall #'(setf zz-s) 1 2)
         (undefined-function (c) (list (cell-error-name c) (princ-to-string c)))))
```

SBCL: `((SETF ZZ-S) "The function (COMMON-LISP:SETF COMMON-LISP-USER::ZZ-S) is undefined.")`.
All four backends: `(|%setf-ZZ-S| "The function %setf-ZZ-S is undefined")` -- the internal
name `LispMacroExpander.setfFunctionName` gives the writer leaks into the condition, and into
the compile-time warning (`the function %setf-ZZ-S is undefined`).

## Plan

- Four-backend fixture first.
- Map the mangled name back to `(setf name)` where the name enters the condition (interpreter
  `CellErrorException`, the JVM pad's name recovery, wasm `_undefined_function`) and in the
  warning text.
