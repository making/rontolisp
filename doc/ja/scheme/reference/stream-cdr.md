# stream-cdr

`(stream-cdr stream)`

空でないストリームの残りを、そのプロミスを強制して返します。空のストリームはエラーになります。残りは初回に計算されて記憶されるため、ストリームを 2 回たどっても各要素の評価は 1 回です。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(stream-car (stream-cdr (stream 1 2 3))) ; => 2
(stream-cdr (stream 1)) ; => ()
```
