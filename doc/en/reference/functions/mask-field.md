# mask-field

`(mask-field bytespec integer)`

The bits of `integer` selected by the [`byte`](byte.md) specifier, left in their original position (unlike [`ldb`](ldb.md), which shifts them down to bit 0).

```lisp
(mask-field (byte 4 4) 255) ; => 240
```
