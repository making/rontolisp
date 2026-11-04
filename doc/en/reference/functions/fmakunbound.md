# fmakunbound

`(fmakunbound function-name)`

Makes `function-name` name no function again, and returns it. An unknown name is a no-op. A `(setf name)` list, quoted or built at run time, retires the `(setf name)` function.

On the interpreter the global function binding (and any `defmacro` macro of the same name) is removed outright, so a later call signals `The function X is undefined`. On the compiled backends a quoted name is retired for every reference, as is a function a [`defun` below the top level](../special-forms/defun.md#below-the-top-level) defines. A name computed at run time retires a top-level `defun` only for **late-bound** references — [`fboundp`](fboundp.md), `funcall`/`apply`/`eval` through a computed name — because a call site the compiler already bound directly cannot be undone. Built-in macros and special forms are part of the language, not of the image's function namespace, so they are not affected.

```lisp
(defun greet (n) n)
(list (fboundp 'greet) (fmakunbound 'greet) (fboundp 'greet)) ; => (T GREET NIL)
```
