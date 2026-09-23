# complement

`(complement function)`

Returns a predicate answering the opposite of `function`: the result is `t` where `function` returns `nil` and vice versa. The returned predicate takes any number of arguments -- calls of up to three go through a `funcall` dispatch, a fourth and beyond through `apply`, so spelling `complement` pulls in the apply runtime on the compiled backends. It serves an equality designator (`:test` / `:test-not`) as well as a one-argument predicate. Lite: `complement` expands inline so `#'complement` is not available.

```lisp
(funcall (complement #'evenp) 3) ; => T
```

```lisp
(remove-if (complement #'oddp) '(1 2 3 4 5)) ; => (1 3 5)
```

```lisp
(remove 3 (list 1 2 3 4) :test-not (complement #'eql)) ; => (1 2 4)
```
