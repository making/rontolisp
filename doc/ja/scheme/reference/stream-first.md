# stream-first

`(stream-first stream)`

`stream-car` と同じで、空でないストリームの先頭要素を返します。R7RS ではなく SICP/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(stream-first (stream 7 8 9)) ; => 7
```
