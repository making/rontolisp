# float

`(float number &optional prototype)`

Converts `number` to a floating-point value (a double). Integers and ratios are turned into their nearest float; a value that is already a float is returned unchanged. The optional `prototype` selects the float subtype in Common Lisp; rontolisp has a single float representation, so it is evaluated and ignored.

```lisp
(float 42) ; => 42.0
```

```lisp
(float 1/2) ; => 0.5
```
