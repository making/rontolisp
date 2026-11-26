# dpb

`(dpb newbyte bytespec integer)`

Deposit byte: returns a copy of `integer` with the field named by the byte specifier `bytespec` (see [`byte`](byte.md)) replaced by the low `size` bits of `newbyte`; the other bits are unchanged. `integer` may be any magnitude on every backend.

```lisp
(dpb 0 (byte 4 0) 255) ; => 240
```
