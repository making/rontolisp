# fold-left

`(fold-left f initial list1 list2 ...)`

リストの要素を左から畳み込みます: `(f (f (f initial e1) e2) e3)`。リストが複数なら `f` はそれまでの結果に続けて各リストの要素を 1 つずつ受け取り、最も短いリストの終わりで止まります。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(fold-left cons '() '(1 2 3)) ; => (((() . 1) . 2) . 3)
(fold-left - 0 '(1 2 3)) ; => -6
(fold-left list '() '(a b) '(1 2)) ; => ((() a 1) b 2)
```
