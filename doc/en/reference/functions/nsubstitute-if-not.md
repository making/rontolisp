# nsubstitute-if-not

`(nsubstitute-if-not new predicate list &key key start end count from-end)`

The destructive variant of [`substitute-if-not`](substitute-if-not.md): rewrites the `car` of every cons whose element the predicate *rejects* and returns the (possibly mutated) original list. A vector or string argument comes back as a fresh sequence instead, matching `substitute-if-not`; see [`nsubstitute-if`](nsubstitute-if.md) for the shared cons-reuse semantics. `:start`/`:end` bound the scanned subsequence -- an element outside it is neither tested nor acted on -- `:count` caps how many matches are acted on, and `:from-end` reverses the order the elements are visited, so with `:count` the matches taken are the last ones. A bound outside the sequence -- negative, not an integer, past its length, or a start past its end -- signals a `type-error` before any element is examined; a nil `:end` means the length.

```lisp
(nsubstitute-if-not 0 #'oddp (list 1 2 3 4 5)) ; => (1 0 3 0 5)
```

```lisp
(nsubstitute-if-not 0 #'oddp (vector 1 2 3)) ; => #(1 0 3)
```

```lisp
(nsubstitute-if-not 0 #'oddp (list 1 2 3 4) :count 1) ; => (1 0 3 4)
```
