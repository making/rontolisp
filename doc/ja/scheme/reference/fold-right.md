# fold-right

`(fold-right f initial list)`

`list` の要素を右から畳み込みます: `(f e1 (f e2 (f e3 initial)))`。受け取るリストは 1 つだけで、MIT Scheme の複数リストを取る形は引数の数で拒否されます。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(fold-right cons '() '(1 2 3)) ; => (1 2 3)
(fold-right - 0 '(1 2 3)) ; => 2
```
