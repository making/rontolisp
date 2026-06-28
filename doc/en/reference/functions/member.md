# member

`(member item list &key test)`

Searches `list` for the first element matching `item` and returns the sublist (tail) starting at that element, or `nil` if none matches. By default the comparison is `eql`; an optional `:test` keyword takes a function designator to use a different comparison. The result shares structure with the original list rather than being a copy.

```lisp
(member 2 '(1 2 3)) ; => (2 3)
```

```lisp
(member '(a d) '((a b) (a d)) :test 'equal) ; => ((a d))
```
