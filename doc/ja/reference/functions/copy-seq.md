# copy-seq

`(copy-seq sequence)`

`sequence` (リストまたは文字列) の新しいコピーを返します。`(subseq sequence 0)` と等価です。コピーは元とコンスセルを共有しないため、どちらかへの破壊的操作がもう一方へ影響することはありません。

```lisp
(let ((original '(1 2 3)))
  (eq (copy-seq original) original)) ; => nil
```

```lisp
(copy-seq "xyz") ; => "xyz"
```
