# mapcan

`(mapcan function list)`

Applies `function` to each element of `list` -- each call should return a list -- and concatenates the results into a single list. rontolisp joins the pieces with non-destructive `append` (it does not splice with `nconc` as standard Common Lisp does). It is a convenient way to map and filter at once: return `nil` for elements to drop. Single-list form only.

The argument must be a list (`nil`, the empty list, is accepted); passing a non-list such as a string signals an error rather than silently returning `nil`. Use `map` to map over a string or vector.

```lisp
(mapcan (lambda (x) (list x x)) '(1 2)) ; => (1 1 2 2)
```
