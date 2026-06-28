# find-if-not

`(find-if-not predicate list)`

Returns the first element of `list` that does **not** satisfy `predicate`, or `nil` if every element satisfies it. It returns the element itself. This is the complement of `find-if`.

```lisp
(find-if-not #'evenp '(2 4 5 6)) ; => 5
```
