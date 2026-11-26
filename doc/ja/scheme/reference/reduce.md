# reduce

`(reduce f initial list)`

`list` の要素を 2 引数の手続き `f` で、SRFI-1 や MIT Scheme と同じく左から畳み込みます。各要素が第 1 引数、それまでの結果が第 2 引数です: `(f e3 (f e2 e1))`。空リストなら `initial` を返し、要素が 1 つのリストなら `f` を呼ばずにその要素を返します。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(reduce + 0 '(1 2 3 4)) ; => 10
(reduce max 0 '(3 9 2)) ; => 9
(reduce + 0 '()) ; => 0
(reduce - 0 '(1 2 3 4)) ; => 2
```
