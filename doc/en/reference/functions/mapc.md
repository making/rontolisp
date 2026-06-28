# mapc

`(mapc function list)`

Applies `function` to each element of `list` for its side effects, discarding the results, and returns the original `list`. Use it instead of `mapcar` when you only care about the effect (such as printing). Single-list form only.

```lisp
(mapc #'print '(1 2 3))
```

```
1
2
3
```
