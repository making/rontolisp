# defparameter

`(defparameter name value)`

Defines a global variable `name` and binds it to `value`, evaluating `value` and **always** (re)assigning even if `name` is already bound -- unlike `defvar`, which only binds an unbound name. Returns the name symbol.

```lisp
(defparameter *limit* 100) ; => *limit*
```

```lisp
(defparameter *limit* 100)
*limit* ; => 100
```
