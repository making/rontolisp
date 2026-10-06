# nsubstitute

`(nsubstitute new old list &key test key start end count from-end)`

The destructive counterpart of `substitute`: rewrites the cars of `list` in place, replacing every element matching `old` with `new`. A vector or string argument has no cars to rewrite, so it comes back as a fresh sequence instead, like `substitute`. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to each element before the comparison. The list structure is reused, so the modification is visible through the original variable. `:start`/`:end` bound the scanned subsequence -- an element outside it is neither tested nor acted on -- `:count` caps how many matches are acted on, and `:from-end` reverses the order the elements are visited, so with `:count` the matches taken are the last ones. A bound outside the sequence -- negative, not an integer, past its length, or a start past its end -- signals a `type-error` before any element is examined; a nil `:end` means the length. A `:count` that is neither an integer nor nil signals a `type-error` the same way, before the bounds are checked; a negative count acts as zero and nil is no limit.

```lisp
(nsubstitute 0 2 '(1 2 3 2)) ; => (1 0 3 0)
```

```lisp
(nsubstitute 'x 2 (list '(1) '(2)) :key #'car) ; => ((1) X)
```

```lisp
(nsubstitute 9 1 (vector 1 2 1)) ; => #(9 2 9)
```

```lisp
(nsubstitute 0 2 (list 1 2 3 2) :count 1) ; => (1 0 3 2)
```
