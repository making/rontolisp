# delete

`(delete x list)`

`list` から `x` と `equal?` な要素を取り除いた新しいリストを返します。`list` 自体は変更しません。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(delete 3 '(1 3 2 3)) ; => (1 2)
(delete "b" '("a" "b" "c")) ; => ("a" "c")
```
