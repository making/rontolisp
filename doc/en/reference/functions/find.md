# find

`(find item sequence &key test test-not key start end from-end)`

Returns the first element of `sequence` that matches `item`, or `nil` if no element matches. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to each element before the comparison (the returned element is the original one, not the keyed value). The sequence may be a list or a string; the elements of a string are characters. Unlike `member`, which returns the matching tail, `find` returns the element itself. Use `position` to obtain the index instead. `:test-not` negates `:test`, `:start`/`:end` bound the scanned region and with `:from-end` true the LAST match wins. A bound outside the sequence -- negative, not an integer, past its length, or a start past its end -- signals a `type-error` before any element is examined; a nil `:end` means the length.

```lisp
(find 2 '(1 2 3)) ; => 2
```

```lisp
(find #\l "hello") ; => #\l
```

```lisp
(find "b" '("a" "b" "c") :test #'string=) ; => "b"
```

```lisp
(find 4 '((1 2) (3 4)) :key #'cadr) ; => (3 4)
```
