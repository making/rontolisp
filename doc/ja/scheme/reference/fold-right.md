# fold-right

`(fold-right f initial list1 list2 ...)`

リストの要素を右から畳み込みます: `(f e1 (f e2 (f e3 initial)))`。リストが複数なら `f` は各リストの要素を 1 つずつ受け取った後にそれまでの結果を受け取り、最も短いリストの終わりで止まります。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(fold-right cons '() '(1 2 3)) ; => (1 2 3)
(fold-right - 0 '(1 2 3)) ; => 2
(fold-right list '() '(a b) '(1 2)) ; => (a 1 (b 2 ()))
```
