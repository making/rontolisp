# mapcon

`(mapcon function list &rest more-lists)`

Like `maplist`, `function` is applied to successive tails of the given lists, but the resulting lists are concatenated into one (the tail-walking counterpart of `mapcan`). The pieces are joined with `append`. When several lists are supplied, the function is called with one tail from each list in parallel, and iteration stops at the end of the shortest list.

Each argument must be a list (`nil`, the empty list, is accepted); passing a non-list such as a string signals an error rather than silently returning `nil`. Use `map` to map over a string or vector.

```lisp
(mapcon (lambda (x) (list (car x))) '(1 2 3)) ; => (1 2 3)
```

```lisp
(mapcon #'list '(1 2) '(3 4)) ; => ((1 2) (3 4) (2) (4))
```
