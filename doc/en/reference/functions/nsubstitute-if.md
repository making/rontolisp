# nsubstitute-if

`(nsubstitute-if new predicate list &key key start end count from-end)`

The destructive variant of [`substitute-if`](substitute-if.md): rewrites the `car` of every cons whose element satisfies the predicate and returns the (possibly mutated) original list. The cons cells are reused, so any other reference to the list observes the change. A vector or string argument has no cons cells to rewrite, so it comes back as a fresh sequence instead, matching `substitute-if`. `:start`/`:end` bound the scanned subsequence -- an element outside it is neither tested nor acted on -- `:count` caps how many matches are acted on, and `:from-end` reverses the order the elements are visited, so with `:count` the matches taken are the last ones. A bound outside the sequence -- negative, not an integer, past its length, or a start past its end -- signals a `type-error` before any element is examined; a nil `:end` means the length. A `:count` that is neither an integer nor nil signals a `type-error` the same way, before the bounds are checked; a negative count acts as zero and nil is no limit.

```lisp
(nsubstitute-if 0 #'oddp (list 1 2 3 4 5)) ; => (0 2 0 4 0)
```

```lisp
(let* ((a (list 1 2 3)) (b a)) (nsubstitute-if 0 #'oddp a) b) ; => (0 2 0)
```

```lisp
(nsubstitute-if 0 #'oddp (vector 1 2 3)) ; => #(0 2 0)
```

```lisp
(nsubstitute-if 0 #'oddp (list 1 2 3) :count 1 :from-end t) ; => (1 2 0)
```
