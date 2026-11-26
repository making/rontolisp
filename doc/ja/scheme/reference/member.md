# member

`(member obj list)` `(member obj list compare)`

`memq` と同様ですが `equal?` で比較します。`compare` 手続きを渡すと、`(compare obj element)` として呼んで比較します。

```scheme
(member (list 'a) '(b (a) c)) ; => ((a) c)
(member "b" '("a" "b" "c")) ; => ("b" "c")
(member 2.0 '(1 2 3) =) ; => (2 3)
```
