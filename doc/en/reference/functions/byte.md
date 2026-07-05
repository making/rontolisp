# byte

`(byte size position)`

Builds a byte specifier naming a field of `size` bits starting at bit `position` (0 = least significant bit), for use with [`ldb`](ldb.md) and [`dpb`](dpb.md). The specifier is an ordinary object whose parts are read back with [`byte-size`](byte-size.md) and [`byte-position`](byte-position.md).

```lisp
(byte-size (byte 8 3)) ; => 8
```
