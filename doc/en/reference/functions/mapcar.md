# mapcar

`(mapcar function list)`

Applies `function` to each element of `list` in turn and returns a new list of the results. The function receives one element per call. rontolisp supports only the single-list form -- mapping in parallel over several lists is not available.

The argument must be a list (`nil`, the empty list, is accepted); passing a non-list such as a string signals an error rather than silently returning `nil`. Use `map` to map over a string or vector.

```lisp
(mapcar #'car '((1 2) (3 4))) ; => (1 3)
```
