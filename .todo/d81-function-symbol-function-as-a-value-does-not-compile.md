# d81. #'symbol-function and #'fdefinition as values do not compile

Difficulty: Low

```lisp
(defun f () 1)
(print (funcall (funcall #'symbol-function 'f)))   ; interpreter and SBCL: 1
(print (funcall (funcall #'fdefinition 'f)))
```

The interpreter prints `1` twice; the JVM, P1 and the component refuse the program with
`Cannot compile: SYMBOL-FUNCTION`.

## Plan

- Four-backend fixture first.
- A reference-gated wrapper like `#'fboundp`'s (`BuiltinFunctionWrappers`), whose body is the
  computed `symbol-function`; its spelling must count in the `usesRuntimeFunctionBox` scan
  (`.kb/symbol-runtime-api.md`, "Any future lowering that synthesizes a computed
  `symbol-function`").
