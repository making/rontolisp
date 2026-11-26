# float-sign

`(float-sign float &optional other)`

Returns the sign of `float` as a float: `1.0` or `-1.0` -- or `other`'s magnitude with `float`'s sign when `other` is given. Negative zero answers `-1.0`.

```lisp
(float-sign -2.5) ; => -1.0
```

```lisp
(float-sign -2.5 3.0) ; => -3.0
```
