# special-operator-p

`(special-operator-p symbol)`

True when the symbol names one of the 25 ANSI special operators -- `block`, `catch`, `eval-when`, `flet`, `function`, `go`, `if`, `labels`, `let`, `let*`, `load-time-value`, `locally`, `macrolet`, `multiple-value-call`, `multiple-value-prog1`, `progn`, `progv`, `quote`, `return-from`, `setq`, `symbol-macrolet`, `tagbody`, `the`, `throw`, `unwind-protect` -- and `nil` for everything else.

`nil` includes the Common Lisp MACROS that rontolisp happens to implement as special forms of its own (`defun`, `handler-case`, `dolist`, ...). The question a caller asks is "may I `apply` this name", and those names answer it through [`macro-function`](macro-function.md) instead, exactly as in Common Lisp.

```lisp
(list (special-operator-p 'if) (special-operator-p 'defun) (special-operator-p 'car)) ; => (T NIL NIL)
```

Lite: a non-symbol argument answers `nil` where Common Lisp signals a type error.
