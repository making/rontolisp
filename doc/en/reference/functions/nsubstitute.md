# nsubstitute

`(nsubstitute new old list &key test key)`

The destructive counterpart of `substitute`: rewrites the cars of `list` in place, replacing every element matching `old` with `new`. A vector or string argument has no cars to rewrite, so it comes back as a fresh sequence instead, like `substitute`. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to each element before the comparison. The list structure is reused, so the modification is visible through the original variable.

```lisp
(nsubstitute 0 2 '(1 2 3 2)) ; => (1 0 3 0)
```

```lisp
(nsubstitute 'x 2 (list '(1) '(2)) :key #'car) ; => ((1) X)
```

```lisp
(nsubstitute 9 1 (vector 1 2 1)) ; => #(9 2 9)
```
