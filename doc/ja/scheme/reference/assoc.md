# assoc

`(assoc obj alist)` `(assoc obj alist compare)`

`assq` と同様ですが `equal?` で比較します。`compare` 手続きを渡すと、`(compare obj key)` として呼んで比較します。

```scheme
(assoc "b" '(("a" . 1) ("b" . 2))) ; => ("b" . 2)
(assoc (list 'a) '(((a)) ((b)))) ; => ((a))
(assoc 5.0 '((2 3) (5 7)) =) ; => (5 7)
```
