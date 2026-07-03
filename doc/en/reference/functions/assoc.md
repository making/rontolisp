# assoc

`(assoc key alist &key test)`

Searches an association list (a list of `(key . value)` pairs) and returns the first pair whose car matches `key`, or `nil` if none matches. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison (e.g. `#'equal` for string keys). The returned pair shares structure with the alist. Use `rassoc` to search by value instead of by key.

```lisp
(assoc 'b '((a . 1) (b . 2))) ; => (b . 2)
```

```lisp
(assoc "b" '(("a" . 1) ("b" . 2)) :test #'equal) ; => ("b" . 2)
```
