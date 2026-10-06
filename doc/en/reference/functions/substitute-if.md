# substitute-if

`(substitute-if new predicate sequence &key key start end count from-end)`

Returns a new sequence in which every element satisfying `predicate` is replaced by `new`; all other elements are kept unchanged. It is [`substitute`](substitute.md) with the `eql` comparison replaced by a predicate call, so it takes no `:test` — the predicate *is* the test. The optional `:key` keyword takes a selector function applied to each element before the predicate sees it (the replacement value is `new` itself, unkeyed). The sequence may be a list, a string or a vector, and the result keeps that kind. The original sequence is not modified; use [`nsubstitute-if`](nsubstitute-if.md) for the destructive version (lists only). `:start`/`:end` bound the scanned subsequence -- an element outside it is neither tested nor acted on -- `:count` caps how many matches are acted on, and `:from-end` reverses the order the elements are visited, so with `:count` the matches taken are the last ones. A bound outside the sequence -- negative, not an integer, past its length, or a start past its end -- signals a `type-error` before any element is examined; a nil `:end` means the length. A `:count` that is neither an integer nor nil signals a `type-error` the same way, before the bounds are checked; a negative count acts as zero and nil is no limit.

```lisp
(substitute-if 0 #'oddp '(1 2 3 4 5)) ; => (0 2 0 4 0)
```

```lisp
(substitute-if #\- (lambda (c) (member c '(#\. #\/) :test 'char=)) "lack/mw.backtrace") ; => "lack-mw-backtrace"
```

```lisp
(substitute-if 0 #'oddp '((1) (2) (3)) :key #'car) ; => (0 (2) 0)
```

```lisp
(substitute-if 0 #'oddp '(1 2 3) :count 1) ; => (0 2 3)
```
