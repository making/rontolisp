# stream-car

`(stream-car stream)`

空でないストリームの先頭要素を返します。空のストリームはエラーになります。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(stream-car (stream 1 2 3)) ; => 1
(stream-car (cons-stream 'a '())) ; => a
```
