# delete-if-not

`(delete-if-not predicate list)`

The destructive counterpart of `remove-if-not`: returns `list` keeping only the elements that satisfy `predicate`, splicing out the rest in place. Because the head may change, use the return value rather than the original variable.

```lisp
(delete-if-not #'evenp '(1 2 3 4)) ; => (2 4)
```
