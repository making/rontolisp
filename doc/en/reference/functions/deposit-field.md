# deposit-field

`(deposit-field newbyte bytespec integer)`

Returns a copy of `integer` with the field named by the byte specifier `bytespec` (see [`byte`](byte.md)) replaced by `newbyte`'s bits AT that field; the other bits are unchanged. Unlike [`dpb`](dpb.md), which deposits `newbyte`'s low `size` bits, the field reads the same positions back out of `newbyte`. `integer` may be any magnitude on every backend.

```lisp
(deposit-field 0 (byte 4 0) 255) ; => 240
```

```lisp
(deposit-field 5 (byte 4 4) 0) ; => 0
```
