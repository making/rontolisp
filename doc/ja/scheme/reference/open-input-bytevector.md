# open-input-bytevector

`(open-input-bytevector bytevector)`

`bytevector` のバイトを読むバイナリ入力ポートを返します（複製を読むので、後で `bytevector` を変えても読む内容は変わりません）。

```scheme
(read-u8 (open-input-bytevector #u8(7 8))) ; => 7
```
