# pushnew

`(pushnew item place &key test key)`

`item` が `place` に格納されたリストのメンバでない場合にのみ先頭へ追加し、結果を書き戻します。メンバかどうかは [`adjoin`](../functions/adjoin.md) と同じく判定します。`item` と各要素を `eql`(または指定した `:test`)で比較し、`:key` があれば両方にそれを適用します。(変化しないこともある)リストを返します。`push` と同様、place は複数回評価されることがあります。

```lisp
(setq ns (list 2 3))
(pushnew 1 ns) ; => (1 2 3)
(pushnew 2 ns) ; => (1 2 3)
ns             ; => (1 2 3)
```

```lisp
(setq pairs (list (list "a" 1)))
(pushnew (list "a" 2) pairs :key #'first :test #'string=) ; => (("a" 1))
```
