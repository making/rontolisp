# rassoc

`(rassoc value alist)`

連想リストを検索し、cdr が `value` と `eql` である最初のペアを返します。一致するものがなければ `nil` を返します。car で検索する `assoc` の対をなすものです。返されるペアは連想リストと構造を共有します。

```lisp
(rassoc 2 (list (cons 'a 1) (cons 'b 2))) ; => (b . 2)
```
