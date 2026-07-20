# nth

`(nth n list)`

Returns the element at zero-based index `n` of `list`. If `n` is greater than or equal to the list length the result is `nil`. Note the argument order: the index comes first, then the list.

```lisp
(nth 2 '(a b c d)) ; => C
```

```lisp
(nth 10 '(a b c)) ; => nil
```
