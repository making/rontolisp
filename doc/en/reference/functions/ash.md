# ash

`(ash integer count)`

Arithmetic shift of `integer` by `count` bit positions: left (toward more significant bits) when `count` is non-negative, right (with sign extension) when `count` is negative. The result is an exact integer of any magnitude on every backend. A left shift whose result cannot be built -- 2147483647 bits or wider, or a count past 33554432 on the wasm-GC backends -- signals a catchable `simple-error` reporting `ASH: shift count too large: ` and the count. Zero stays zero at any count, and a right shift past the value's width answers `0` or `-1`.

```lisp
(ash 1 4) ; => 16
```

```lisp
(ash 255 -4) ; => 15
```
