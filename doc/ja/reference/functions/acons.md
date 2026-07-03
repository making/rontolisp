# acons

`(acons key datum alist)`

ペア `(key . datum)` を `alist` の先頭に追加した新しい連想リストを返します。`(cons (cons key datum) alist)` の省略形で、元のリストは変更しません。新しいペアは、同じキーの既存エントリより優先して `assoc` の検索にヒットします。

```lisp
(acons 'b 2 '((a . 1))) ; => ((b . 2) (a . 1))
```
