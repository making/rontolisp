# both-case-p

`(both-case-p character)`

文字が大文字・小文字の両形を持つ英字なら真を返します(`lower-case-p` または `upper-case-p`)。文字でない引数は、演算子名を付けた `type-error` を通知します。

```lisp
(both-case-p #\a) ; => T
```

```lisp
(both-case-p #\5) ; => NIL
```
