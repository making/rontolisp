# make-symbol

`(make-symbol string)`

`#:<string>` という名前の新しいアンインターンドシンボルを返します — [`gensym`](gensym.md) と同じ `#:` 規約です。rontolisp にはインターンテーブルがない(シンボルは名前で比較される)ため、「アンインターンド」は `#:` の名前プレフィックスで表現されます: 結果は同じ綴りの普通のシンボルとは決して `eq` になりませんが、同じ文字列で `make-symbol` を 2 回呼ぶと `eq` なシンボルが返ります(すべての `make-symbol` 結果が別オブジェクトになる Common Lisp からの相違点)。一意性が保証された一時変数にはカウンタを付加する `gensym` を使ってください。

```lisp
(make-symbol "temp") ; => #:|temp|
```

```lisp
(eq (make-symbol "foo") 'foo) ; => NIL
```

```lisp
(symbolp (make-symbol "temp")) ; => T
```
