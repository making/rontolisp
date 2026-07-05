# pushnew

`(pushnew item place &key test key)`

`item` が `place` に格納されたリストのメンバでない場合(比較は `eql`、または指定した `:test`)にのみ先頭へ追加し、結果を書き戻します。(変化しないこともある)リストを返します。`push` と同様、place は複数回評価されることがあります。

```lisp
(setq ns (list 2 3))
(pushnew 1 ns) ; => (1 2 3)
(pushnew 2 ns) ; => (1 2 3)
ns             ; => (1 2 3)
```
