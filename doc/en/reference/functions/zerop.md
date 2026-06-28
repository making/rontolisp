# zerop

`(zerop number)`

Returns `t` if `number` is zero, else `nil`. It works for any numeric type; an integer, float or ratio of value zero all satisfy it.

```lisp
(zerop 0) ; => t
```

```lisp
(zerop 0.0) ; => t
```
