# maplist

`(maplist function list &rest more-lists)`

Like `mapcar`, but `function` is applied to successive cdrs (tails) of the given lists rather than to their elements: first the whole list, then its rest, and so on down to the last single-element tail. Returns a new list of the results. When several lists are supplied, the function is called with one tail from each list in parallel, and iteration stops at the end of the shortest list.

Each argument must be a list (`nil`, the empty list, is accepted); passing a non-list such as a string signals an error rather than silently returning `nil`. Use `map` to map over a string or vector.

```lisp
(maplist #'identity '(1 2 3)) ; => ((1 2 3) (2 3) (3))
```

```lisp
(maplist #'list '(1 2) '(3 4)) ; => (((1 2) (3 4)) ((2) (4)))
```
