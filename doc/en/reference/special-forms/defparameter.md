# defparameter

`(defparameter name value)`

Defines a global variable `name` and binds it to `value`, evaluating `value` and **always** (re)assigning even if `name` is already bound -- unlike `defvar`, which only binds an unbound name. Like `defvar`, it proclaims `name` **special**, so a later [`let`](let.md) of it establishes a dynamic binding. Returns the name symbol.

```lisp
(defparameter *limit* 100) ; => *limit*
```

```lisp
(defparameter *limit* 100)
(list (let ((*limit* 5)) *limit*) *limit*) ; => (5 100)
```
