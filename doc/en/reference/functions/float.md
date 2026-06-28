# float

`(float number)`

Converts `number` to a floating-point value (a double). Integers and ratios are turned into their nearest float; a value that is already a float is returned unchanged.

```lisp
(float 42) ; => 42.0
```

```lisp
(float 1/2) ; => 0.5
```
