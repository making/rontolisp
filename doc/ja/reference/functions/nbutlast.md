# nbutlast

`(nbutlast list &optional n)`

破壊的な [`butlast`](butlast.md)。`list` そのものの末尾 `n` 個(既定は 1)の cons を `rplacd` で切り離し、同じリストを返します。何も残らない場合は切る cons がないため、引数はそのままで nil を返します。呼び出し後は戻り値以外で引数を使わないでください。

```lisp
(nbutlast (list 1 2 3 4) 2) ; => (1 2)
```

```lisp
(nbutlast (list 1 2 3) 5) ; => NIL
```
