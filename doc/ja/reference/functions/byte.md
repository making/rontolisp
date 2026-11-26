# byte

`(byte size position)`

ビット `position`（0 = 最下位ビット）から始まる `size` ビットのフィールドを表すバイト指定子を作ります。[`ldb`](ldb.md) や [`dpb`](dpb.md) と組み合わせて使います。指定子は通常のオブジェクトで、その各要素は [`byte-size`](byte-size.md) と [`byte-position`](byte-position.md) で取り出せます。

```lisp
(byte-size (byte 8 3)) ; => 8
```
