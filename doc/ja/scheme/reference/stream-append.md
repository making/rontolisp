# stream-append

`(stream-append stream ...)`

各引数ストリームの要素を順に並べたストリームを、遅延して返します。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(stream->list (stream-append (stream 1 2) (stream 3) (stream 4 5))) ; => (1 2 3 4 5)
(stream-append) ; => ()
```
