# linalg:array-equal

`(linalg:array-equal a b)`

Returns `t` when `a` and `b` have the same shape and numerically equal elements, `nil` otherwise. Numbers are compared with `=`, so `1` and `1.0` compare equal (like numpy's `array_equal`). This is the way to compare linalg results: arrays themselves compare by identity only (`eq`), so two separately built arrays are never `equal`.

```lisp
(linalg:array-equal (linalg:eye 2) #2A((1 0) (0 1))) ; => t
(linalg:array-equal #(1 2) #(1 2 3))                  ; => nil
```
