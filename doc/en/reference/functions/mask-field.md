# mask-field

`(mask-field bytespec integer)`

The bits of `integer` selected by the [`byte`](byte.md) specifier, left in their original position (unlike [`ldb`](ldb.md), which shifts them down to bit 0).

Supported on the **interpreter only** for now; the JVM and WASM compilers do not support it yet.

```lisp
(mask-field (byte 4 4) 255) ; => 240
```
