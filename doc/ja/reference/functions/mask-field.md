# mask-field

`(mask-field bytespec integer)`

[`byte`](byte.md) 指定子が選択する `integer` のビットを、元の位置のまま返します ([`ldb`](ldb.md) がビット 0 まで右シフトするのと異なります)。

現時点では**インタープリタのみ**でサポートされます。JVM / WASM コンパイラは未対応です。

```lisp
(mask-field (byte 4 4) 255) ; => 240
```
