# i17. `defmacro` rejects a destructuring pattern under `&optional`

Difficulty: Medium

Measured 2026-11-04.

```lisp
(defmacro m3 (a &optional ((() &body forms) '(()))) ...)
; Parameter must be a symbol: (NIL &BODY FORMS)
```

Legal per CLHS 3.4.4. `(() &body forms)` outside `&optional` works.

Consumer: eclector's `fixup-case` (`code/reader/labeled-objects.lisp:266`), so coalton stops before it
reaches a component with an `:around-compile` hook.
