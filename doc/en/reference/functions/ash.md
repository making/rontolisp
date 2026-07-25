# ash

`(ash integer count)`

Arithmetic shift of `integer` by `count` bit positions: left (toward more significant bits) when `count` is non-negative, right (with sign extension) when `count` is negative. On the interpreter and JVM the result is an exact integer of any magnitude; on WASM the result is exact within the signed 64-bit range (a shift past 63 bits wraps).

```lisp
(ash 1 4) ; => 16
```

```lisp
(ash 255 -4) ; => 15
```
