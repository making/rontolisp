# h60. `macro-function` is non-nil for operators `macroexpand-1` does not expand

Difficulty: Medium

Found 2026-10-30 loading `access` (h49). On the interpreter:

```lisp
(list (macro-function 'return) (macro-function 'typep)
      (multiple-value-list (macroexpand-1 '(return 1))))
;; => (non-nil non-nil ((RETURN 1) NIL))
```

iterate's code walker (`iterate.lisp:704-717`) trusts `macro-function`, calls
`macroexpand-1`, and on `expanded-p` nil prints "The form ... is a macro that won't
expand" for every `(return ...)`/`(typep ...)` inside an `iter` clause. `typep` is a
FUNCTION in CL, so `macro-function` must answer nil for it; for a CL macro rontolisp
implements natively (`return`), `macroexpand-1` must expand it (to `return-from nil`) or
`macro-function` must not claim it. See `.kb/symbol-runtime-api.md` and `.todo/042`.
