# fmakunbound

`(fmakunbound symbol)`

Makes `symbol` name no function again, and returns the symbol. An unknown name is a no-op.

On the interpreter the global function binding (and any `defmacro` macro of the same name) is removed outright, so a later call signals `The function X is undefined`. On the compiled backends the name is retired only for **late-bound** references — [`fboundp`](fboundp.md), `funcall`/`#'name`/`eval` through the symbol — because a call site the compiler already bound directly cannot be undone. Built-in macros and special forms are part of the language, not of the image's function namespace, so they are not affected.

```lisp
(defun greet (n) n)
(list (fboundp 'greet) (fmakunbound 'greet) (fboundp 'greet)) ; => (T GREET NIL)
```
