# make-list

`(make-list size &key initial-element)`

Returns a freshly allocated proper list of `size` elements, every one of which is `initial-element` (`nil` by default). A `size` of `0` yields the empty list. The element form is evaluated ONCE and every cell shares that one value, as Common Lisp specifies -- so a mutable element is the same object in every cell. Any other keyword is an error.

`size` must be an integer below `array-dimension-limit`, the bound a `make-array` dimension has. One that is not -- a bignum, a negative number, a non-integer -- signals a `type-error` whose datum is `size` and whose expected type is `(INTEGER 0 (limit))`, before any cell is allocated and after the `size` and `initial-element` forms have been evaluated, in that order. The limit is the backend's own: 2147483639 on the interpreter and the JVM, 1073741823 on WASM.

```lisp
(make-list 3) ; => (NIL NIL NIL)
```

```lisp
(make-list 3 :initial-element 0) ; => (0 0 0)
```

```lisp
(handler-case (make-list -1)
  (type-error (e) (type-error-datum e))) ; => -1
```
