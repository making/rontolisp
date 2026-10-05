# substitute-if-not

`(substitute-if-not new predicate sequence &key key start end count from-end)`

The complement of [`substitute-if`](substitute-if.md): returns a new sequence in which every element the predicate *rejects* is replaced by `new`. Takes the same optional `:key` selector, keeps the sequence kind, and does not modify the original; the destructive version is [`nsubstitute-if-not`](nsubstitute-if-not.md). `:start`/`:end` bound the scanned subsequence -- an element outside it is neither tested nor acted on -- `:count` caps how many matches are acted on, and `:from-end` reverses the order the elements are visited, so with `:count` the matches taken are the last ones. A bound outside the sequence -- negative, not an integer, past its length, or a start past its end -- signals a `type-error` before any element is examined; a nil `:end` means the length.

```lisp
(substitute-if-not 0 #'oddp '(1 2 3 4 5)) ; => (1 0 3 0 5)
```

```lisp
(substitute-if-not 'keep #'stringp '("a" 1 "b")) ; => ("a" KEEP "b")
```

```lisp
(substitute-if-not 0 #'oddp '(1 2 3 4) :count 1) ; => (1 0 3 4)
```
