# byte-position

`(byte-position bytespec)`

[`byte`](byte.md) で作ったバイト指定子 `bytespec` の位置（開始ビットのオフセット、0 = 最下位ビット）を返します。

```lisp
(byte-position (byte 8 3)) ; => 3
```
