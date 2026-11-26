# fmakunbound

`(fmakunbound function-name)`

Makes `function-name` name no function again, and returns it. An unknown name is a no-op. A `(setf name)` list, quoted or built at run time, retires the `(setf name)` function.

On the interpreter the global function binding (and any `defmacro` macro of the same name) is removed outright, so a later call signals `The function X is undefined`. On the compiled backends a quoted name is retired for every reference, as is a function a [`defun` below the top level](../special-forms/defun.md#below-the-top-level) defines, and a name computed at run time as well ([`fdefinition`](fdefinition.md) has the one exception, a name assembled out of computed strings). The next `defun` of the name defines it again where the form runs; a generic function's next `defmethod` or `defgeneric` binds its name again, its earlier methods included (SBCL makes a new generic function without them). Built-in macros and special forms are part of the language, not of the image's function namespace, so they are not affected.

```lisp
(defun greet (n) n)
(list (fboundp 'greet) (fmakunbound 'greet) (fboundp 'greet)) ; => (T GREET NIL)
```
