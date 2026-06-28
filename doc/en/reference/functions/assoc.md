# assoc

`(assoc key alist)`

Searches an association list (a list of `(key . value)` pairs) and returns the first pair whose car is `eql` to `key`, or `nil` if none matches. The returned pair shares structure with the alist. Use `rassoc` to search by value instead of by key.

```lisp
(assoc 'b '((a 1) (b 2))) ; => (b 2)
```
