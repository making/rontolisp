# filter

`(filter pred list)`

`list` の要素のうち `pred` が真の値を返すものを、元の順序のまま新しいリストにして返します。`#f` 以外の値はすべて真です。R7RS ではなく SICP/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(filter odd? '(1 2 3 4 5)) ; => (1 3 5)
(filter (lambda (x) (> x 10)) '(1 2 3)) ; => ()
```
