# memq

`(memq obj list)`

car が `obj` である（`eq?` で比較）最初の `list` の部分リストを返します。見つからなければ `#f` を返します。

```scheme
(memq 'c '(a b c d)) ; => (c d)
(memq 'z '(a b c)) ; => #f
(memq (list 'a) '(b (a) c)) ; => #f
```
