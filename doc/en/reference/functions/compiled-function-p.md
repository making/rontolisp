# compiled-function-p

`(compiled-function-p object)`

True for every function, which is what `(typep object 'compiled-function)` answers too: no distinction between compiled and interpreted function objects is recorded, and SBCL answers the same.

```lisp
(list (compiled-function-p #'car) (compiled-function-p 'car)) ; => (T NIL)
```
