# make-random-state

`(make-random-state &optional state)`

常に `nil` を返します。rontolisp には random-state オブジェクトがありません。[`random`](random.md) は省略可能な random-state 引数を受け取って無視し、バックエンド自身のエントロピー源から乱数を取り出すため、`(make-random-state t)` を変数に保存して `random` に渡し直す一般的なイディオムはそのまま動きます(uuid の `*uuid-random-state*` が動機となった利用例です)。引数(`nil`、`t`、state)は受理された上で無視されます。

```lisp
(make-random-state t) ; => NIL
```
