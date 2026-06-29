# mapc

`(mapc function list)`

`list` の各要素に対して副作用のために `function` を適用し、結果は捨てて元の `list` を返します。出力（表示など）の効果だけが目的のときに `mapcar` の代わりに使います。単一リストの形式のみ対応しています。

```lisp
(mapc #'print '(1 2 3))
```

```
1
2
3
```
