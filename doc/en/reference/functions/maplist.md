# maplist

`(maplist function list)`

Like `mapcar`, but `function` is applied to successive cdrs (tails) of `list` rather than to its elements: first the whole list, then its rest, and so on down to the last single-element tail. Returns a new list of the results. Single-list form only.

The argument must be a list (`nil`, the empty list, is accepted); passing a non-list such as a string signals an error rather than silently returning `nil`. Use `map` to map over a string or vector.

```lisp
(maplist #'identity '(1 2 3)) ; => ((1 2 3) (2 3) (3))
```
