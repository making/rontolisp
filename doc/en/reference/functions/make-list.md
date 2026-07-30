# make-list

`(make-list size &key initial-element)`

Returns a freshly allocated proper list of `size` elements, every one of which is `initial-element` (`nil` by default). A `size` of `0` yields the empty list. The element form is evaluated ONCE and every cell shares that one value, as Common Lisp specifies -- so a mutable element is the same object in every cell. Any other keyword is an error.

```lisp
(make-list 3) ; => (NIL NIL NIL)
```

```lisp
(make-list 3 :initial-element 0) ; => (0 0 0)
```
