# the-empty-stream

`the-empty-stream`

空のストリームを保持する変数で、その値は空リスト `'()` です。R7RS ではなく SICP/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
the-empty-stream ; => ()
(stream-null? the-empty-stream) ; => #t
```
