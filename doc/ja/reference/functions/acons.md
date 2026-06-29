# acons

`(acons key datum alist)`

`alist` の先頭にペア `(key . datum)` を追加した新しい連想リストを返します。`(cons (cons key datum) alist)` の省略形であり、元のリストは変更しません。新しいペアは、`assoc` の検索において同じキーを持つ既存のエントリを隠します。

```lisp
(acons 'b 2 (list (cons 'a 1))) ; => ((b . 2) (a . 1))
```
