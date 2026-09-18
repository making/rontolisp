# stream

`(stream obj ...)`

引数を順に並べた有限ストリームを返します。引数がなければ空のストリームです。R7RS ではなく SICP/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(stream->list (stream 1 2 3)) ; => (1 2 3)
(stream) ; => ()
```
