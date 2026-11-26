# last-pair

`(last-pair list)`

空でないリストの最後のペア、つまり cdr がリストの終端であるペアを返します。非真正リストでは、最後のドット対の末尾を持つペアです。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(last-pair '(1 2 3)) ; => (3)
(last-pair '(1 2 . 3)) ; => (2 . 3)
```
