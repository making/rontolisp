# mapc

`(mapc function list)`

Applies `function` to each element of `list` for its side effects, discarding the results, and returns the original `list`. Use it instead of `mapcar` when you only care about the effect (such as printing). Single-list form only.

The argument must be a list (`nil`, the empty list, is accepted); passing a non-list such as a string signals an error rather than silently doing nothing. Use `map` to map over a string or vector.

```lisp
(mapc #'print '(1 2 3))
```

```
1
2
3
```
