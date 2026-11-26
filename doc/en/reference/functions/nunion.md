# nunion

`(nunion list-1 list-2 &key test test-not key)`

The set union of two lists, like [`union`](union.md). CL permits `nunion` to reuse the argument cells; rontolisp answers the non-destructive result instead, which the standard allows, so the arguments are never modified. The result order is unspecified.

```lisp
(nunion (list 1 2 3) (list 2 3 4)) ; => (4 1 2 3)
```

```lisp
(nunion (list "a") (list "A") :test #'string-equal) ; => ("a")
```
