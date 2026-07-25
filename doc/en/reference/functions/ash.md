# ash

`(ash integer count)`

Arithmetic shift of `integer` by `count` bit positions: left (toward more significant bits) when `count` is non-negative, right (with sign extension) when `count` is negative. The result is an exact integer of any magnitude on every backend.

```lisp
(ash 1 4) ; => 16
```

```lisp
(ash 255 -4) ; => 15
```
