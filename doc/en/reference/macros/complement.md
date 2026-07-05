# complement

`(complement function)`

Returns a one-argument predicate answering the opposite of `function`: the result is `t` where `function` returns `nil` and vice versa. Lite: unlike Common Lisp the returned function takes exactly one argument, and `complement` expands inline so `#'complement` is not available.

```lisp
(funcall (complement #'evenp) 3) ; => t
```

```lisp
(remove-if (complement #'oddp) '(1 2 3 4 5)) ; => (1 3 5)
```
