# nreconc

`(nreconc list tail)`

`revappend` の破壊的バージョンです。`list` をその場で反転し `tail` を連結します。`list` のコンスセルを再利用します（`(nconc (nreverse list) tail)` に展開されます）。入力リストが繋ぎ替えられるため、新しいリストを渡し、元の変数ではなく戻り値を使用してください。

```lisp
(nreconc (list 1 2 3) '(4 5)) ; => (3 2 1 4 5)
```
