# ldb

`(ldb bytespec integer)`

Load byte: extracts the field of `integer` named by the byte specifier `bytespec` (see [`byte`](byte.md)) and returns it right-justified, so the field's least significant bit becomes bit 0. `integer` may be any magnitude on every backend.

```lisp
(ldb (byte 4 4) 255) ; => 15
```
