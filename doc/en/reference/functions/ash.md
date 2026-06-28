# ash

`(ash integer count)`

Arithmetic shift of `integer` by `count` bit positions: left (toward more significant bits) when `count` is non-negative, right (with sign extension) when `count` is negative. On the interpreter and JVM the result is an exact integer of any magnitude; on WASM values are 31-bit `i31`.

```lisp
(ash 1 4) ; => 16
```

```lisp
(ash 255 -4) ; => 15
```
