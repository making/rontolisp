# stream-rest

`(stream-rest stream)`

`stream-cdr` と同じで、空でないストリームの残りを返します。強制は 1 回だけで、結果は記憶されます。R7RS ではなく SICP/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(stream-head (stream-rest (stream 7 8 9)) 2) ; => (8 9)
```
