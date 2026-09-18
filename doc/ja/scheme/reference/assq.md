# assq

`(assq obj alist)`

連想リスト `alist` のうち、car が `obj` である（`eq?` で比較）最初のペアを返します。見つからなければ `#f` を返します。

```scheme
(assq 'b '((a 1) (b 2))) ; => (b 2)
(assq 'c '((a 1) (b 2))) ; => #f
```
