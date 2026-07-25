# ldb

`(ldb bytespec integer)`

Load byte: extracts the field of `integer` named by the byte specifier `bytespec` (see [`byte`](byte.md)) and returns it right-justified, so the field's least significant bit becomes bit 0. On the interpreter and JVM `integer` may be any magnitude; on WASM the field is read within the signed 64-bit range.

```lisp
(ldb (byte 4 4) 255) ; => 15
```
