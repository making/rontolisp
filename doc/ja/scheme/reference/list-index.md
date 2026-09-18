# list-index

`(list-index pred list)`

`pred` が真の値を返す最初の要素の位置（0 始まり）を返し、見つからなければ `#f` を返します。受け取るリストは 1 つだけです。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(list-index even? '(1 3 4 5)) ; => 2
(list-index even? '(1 3 5)) ; => #f
```
