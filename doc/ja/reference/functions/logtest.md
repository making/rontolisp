# logtest

`(logtest integer-1 integer-2)`

`integer-1` と `integer-2` に共通して立っているビットがあるかを調べます。`(not (zerop (logand integer-1 integer-2)))` と等価です。共通のビットが立っていれば `t`、そうでなければ `nil` を返します。両引数はどのバックエンドでも任意の大きさを取れます。

```lisp
(logtest 1 3) ; => T
```

```lisp
(logtest 1 2) ; => NIL
```
