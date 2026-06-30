# mapcon

`(mapcon function list)`

Like `maplist`, `function` is applied to successive tails of `list`, but the resulting lists are concatenated into one (the tail-walking counterpart of `mapcan`). The pieces are joined with `append`. Single-list form only.

The argument must be a list (`nil`, the empty list, is accepted); passing a non-list such as a string signals an error rather than silently returning `nil`. Use `map` to map over a string or vector.

```lisp
(mapcon (lambda (x) (list (car x))) '(1 2 3)) ; => (1 2 3)
```
