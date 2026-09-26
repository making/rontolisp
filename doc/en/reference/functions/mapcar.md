# mapcar

`(mapcar function list &rest more-lists)`

Applies `function` to successive elements of the given lists and returns a new list of the results. With a single list, the function receives one element per call. When several lists are supplied, the function is called with one element from each list in parallel, and iteration stops at the end of the shortest list.

Each argument must be a list (`nil`, the empty list, is accepted); passing a non-list such as a string, or a dotted list the walk reaches the end of, signals a `type-error` rather than silently returning `nil`. Use `map` to map over a string or vector.

```lisp
(mapcar #'car '((1 2) (3 4))) ; => (1 3)
```

```lisp
(mapcar #'+ '(1 2 3 4) '(10 20 30 40)) ; => (11 22 33 44)
```

```lisp
(handler-case (mapcar #'1+ 5) (type-error (e) (princ-to-string e))) ; => "MAPCAR: The value 5 is not of type LIST"
```
