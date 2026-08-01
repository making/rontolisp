# substitute-if

`(substitute-if new predicate sequence &key key)`

Returns a new sequence in which every element satisfying `predicate` is replaced by `new`; all other elements are kept unchanged. It is [`substitute`](substitute.md) with the `eql` comparison replaced by a predicate call, so it takes no `:test` — the predicate *is* the test. The optional `:key` keyword takes a selector function applied to each element before the predicate sees it (the replacement value is `new` itself, unkeyed). The sequence may be a list, a string or a vector, and the result keeps that kind. The original sequence is not modified; use [`nsubstitute-if`](nsubstitute-if.md) for the destructive version (lists only).

```lisp
(substitute-if 0 #'oddp '(1 2 3 4 5)) ; => (0 2 0 4 0)
```

```lisp
(substitute-if #\- (lambda (c) (member c '(#\. #\/) :test 'char=)) "lack/mw.backtrace") ; => "lack-mw-backtrace"
```

```lisp
(substitute-if 0 #'oddp '((1) (2) (3)) :key #'car) ; => (0 (2) 0)
```
