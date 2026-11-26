# butlast

`(butlast list &optional n)`

`list` の末尾 `n` 個(既定は 1)の cons を除いた新しいコピーを返します。元のリストは変更されません。長さ以上の個数を指定すると `nil` を返します。ドットリストでは個数は cons 単位で数えるため、終端のアトムは含まれません。破壊的な版は [`nbutlast`](nbutlast.md) を参照してください。

```lisp
(butlast '(1 2 3 4)) ; => (1 2 3)
```

```lisp
(butlast '(1 2 3 4) 2) ; => (1 2)
```
