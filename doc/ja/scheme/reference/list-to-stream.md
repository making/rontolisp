# list->stream

`(list->stream list)`

`list` の要素を順に並べた有限ストリームを返します。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(stream->list (list->stream '(a b c))) ; => (a b c)
(stream-pair? (list->stream '(1))) ; => #t
```
