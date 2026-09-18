# stream-for-each

`(stream-for-each proc stream)`

有限の `stream` の各要素に対して、効果のために `proc` を順に呼びます。受け取るストリームは 1 つだけです。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(stream-for-each (lambda (x) (display x) (newline)) (stream 1 2 3))
```

```
1
2
3
```
