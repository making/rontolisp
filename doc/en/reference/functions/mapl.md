# mapl

`(mapl function list &rest more-lists)`

Like [`maplist`](maplist.md), but `function` is applied to the successive cdrs (tails) of the given lists for its side effects only, and the first list is returned rather than a list of the results. When several lists are supplied, the function is called with one tail from each list in parallel, and iteration stops at the end of the shortest list.

Each argument must be a list (`nil`, the empty list, is accepted); passing a non-list such as a string signals an error.

```lisp
(mapl #'identity '(1 2 3)) ; => (1 2 3)
```

```lisp
(mapl (lambda (a b) (print (list a b))) '(1 2) '(3 4))
```

```
((1 2) (3 4))
((2) (4))
```
