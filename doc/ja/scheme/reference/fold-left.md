# fold-left

`(fold-left f initial list)`

`list` の要素を左から畳み込みます: `(f (f (f initial e1) e2) e3)`。受け取るリストは 1 つだけで、MIT Scheme の複数リストを取る形は引数の数で拒否されます。R7RS ではなく SICP/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(fold-left cons '() '(1 2 3)) ; => (((() . 1) . 2) . 3)
(fold-left - 0 '(1 2 3)) ; => -6
```
