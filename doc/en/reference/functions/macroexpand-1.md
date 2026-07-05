# macroexpand-1

`(macroexpand-1 form)`

Expands `form` once when its operator is a user macro (defined with [`defmacro`](../special-forms/defmacro.md)) or a built-in macro (the names `rontolisp:list-macros` reports), and returns the form unchanged otherwise. Only the top-level operator is expanded; subforms are left alone.

Deviations from Common Lisp: `macroexpand-1` supplies no second `expanded-p` value — compare the result with the input to detect expansion — and takes no environment argument. On the compilation path the argument must be a literal quoted form: the CLI folds the call to its expansion at compile time (the macro table does not exist at runtime in compiled output), so a computed argument is a compile error, and the runtime `eval` of a compiled program does not know `macroexpand-1`.

```lisp
(macroexpand-1 '(unless c x)) ; => (if c nil x)
```

```lisp
(defmacro my-when (test &body body)
  `(if ,test (progn ,@body) nil))
(macroexpand-1 '(my-when (> 2 1) 'a 'b)) ; => (if (> 2 1) (progn (quote a) (quote b)) nil)
```

A non-macro form is returned as-is:

```lisp
(macroexpand-1 '(+ 1 2)) ; => (+ 1 2)
```
