# mapc

`(mapc function list &rest more-lists)`

Applies `function` to successive elements of the given lists for its side effects, discarding the results, and returns the first list. Use it instead of `mapcar` when you only care about the effect (such as printing). With a single list, the function receives one element per call. When several lists are supplied, the function is called with one element from each list in parallel, and iteration stops at the end of the shortest list.

Each argument must be a list (`nil`, the empty list, is accepted); passing a non-list such as a string signals an error rather than silently doing nothing. Use `map` to map over a string or vector.

```lisp
(mapc #'print '(1 2 3))
```

```
1
2
3
```

```lisp
(mapc (lambda (a b) (print (list a b))) '(1 2) '(3 4))
```

```
(1 3)
(2 4)
```
