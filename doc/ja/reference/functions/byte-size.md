# byte-size

`(byte-size bytespec)`

[`byte`](byte.md) で作ったバイト指定子 `bytespec` のサイズ（ビット数）を返します。

```lisp
(byte-size (byte 8 3)) ; => 8
```
