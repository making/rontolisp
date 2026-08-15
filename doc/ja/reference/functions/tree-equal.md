# tree-equal

`(tree-equal tree-1 tree-2 &key test test-not)`

2 つのコンスツリーが同じ形をしていて、対応する葉がすべて一致するとき `t` を返します。コンスはコンスとしか一致しないので、同じ位置に部分木と葉があれば異なります。葉は `:test`(既定 `eql`)で比較し、`:test-not` を渡した場合はその関数が偽を返す箇所を一致とみなします。なお本実装では、同じ文字を持つ 2 つの文字列は最初から `eql` である点が多くの Common Lisp と異なります。

```lisp
(tree-equal '(1 (2 3)) '(1 (2 3))) ; => T
```

```lisp
(tree-equal '(1 (2)) '(1 2)) ; => NIL
```

```lisp
(tree-equal '("a" ("b")) '("A" ("B")) :test #'string-equal) ; => T
```
