# stream-null?

`(stream-null? obj)`

`obj` が空のストリーム `'()` なら `#t`、そうでなければ `#f` を返します。`null?` と同じ検査です。R7RS ではなく SICP/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(stream-null? the-empty-stream) ; => #t
(stream-null? (stream 1)) ; => #f
```
