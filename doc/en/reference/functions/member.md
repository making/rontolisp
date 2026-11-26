# member

`(member item list &key test key)`

Searches `list` for the first element matching `item` and returns the sublist (tail) starting at that element, or `nil` if none matches. By default the comparison is `eql`; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to each element before the comparison. The result shares structure with the original list rather than being a copy. A `list` that is not a list, or a dotted list the search reaches the end of, signals a `type-error`.

```lisp
(member 2 '(1 2 3)) ; => (2 3)
```

```lisp
(member '(a d) '((a b) (a d)) :test 'equal) ; => ((A D))
```

```lisp
(member 3 '((1 2) (3 4) (5 6)) :key #'car) ; => ((3 4) (5 6))
```

```lisp
(handler-case (member 1 5) (type-error (e) (princ-to-string e))) ; => "MEMBER: The value 5 is not of type LIST"
```
