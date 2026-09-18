# empty-stream?

`(empty-stream? obj)`

`stream-null?` と同じで、`obj` が空のストリーム `'()` なら `#t` を返します。R7RS ではなく SICP/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(empty-stream? (stream)) ; => #t
(empty-stream? (stream 1)) ; => #f
```
