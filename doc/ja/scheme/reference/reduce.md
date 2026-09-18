# reduce

`(reduce f initial list)`

`list` の要素を 2 引数の手続き `f` で畳み込みます。空リストなら `initial` を返し、要素が 1 つのリストなら `f` を呼ばずにその要素を返します。R7RS ではなく SICP/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(reduce + 0 '(1 2 3 4)) ; => 10
(reduce max 0 '(3 9 2)) ; => 9
(reduce + 0 '()) ; => 0
```
