# binary-port?

`(binary-port? obj)`

`obj` がバイナリポート（バイトベクタポート）なら `#t` を返します。標準のポートと文字列ポートはバイナリではありません（Gauche では両方です）。

```scheme
(binary-port? (open-input-bytevector #u8(1))) ; => #t
```
