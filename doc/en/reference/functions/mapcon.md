# mapcon

`(mapcon function list)`

Like `maplist`, `function` is applied to successive tails of `list`, but the resulting lists are concatenated into one (the tail-walking counterpart of `mapcan`). The pieces are joined with `append`. Single-list form only.

```lisp
(mapcon (lambda (x) (list (car x))) '(1 2 3)) ; => (1 2 3)
```
