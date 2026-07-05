# nth-value

`(nth-value n values-form)`

`values-form` の `n` 番目（0 始まり）の値を返します。該当する値がなければ nil です。`n` はフォームより先に評価されます。[`multiple-value-list`](multiple-value-list.md) の上の `nth` に展開されるため、プロデューサは [`multiple-value-bind`](multiple-value-bind.md) と同様に認識され、結果が `(values ...)` 呼び出しであるユーザ関数も含まれます。

```lisp
(nth-value 1 (floor 7 2)) ; => 1
```

```lisp
(nth-value 0 (values 'a 'b)) ; => a
```
