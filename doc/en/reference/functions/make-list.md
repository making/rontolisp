# make-list

`(make-list size)`

Returns a freshly allocated proper list of `size` elements, every one of which is `nil`. The `:initial-element` keyword of full Common Lisp is not supported, so the fill value is always `nil`. A `size` of `0` yields the empty list.

```lisp
(make-list 3) ; => (NIL NIL NIL)
```
