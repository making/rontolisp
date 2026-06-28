# mapcar

`(mapcar function list)`

Applies `function` to each element of `list` in turn and returns a new list of the results. The function receives one element per call. rontolisp supports only the single-list form -- mapping in parallel over several lists is not available.

```lisp
(mapcar #'car '((1 2) (3 4))) ; => (1 3)
```
