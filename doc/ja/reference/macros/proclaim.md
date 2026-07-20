# proclaim

`(proclaim declaration)`

`declaim` と同様にパースされるだけの no-op で、フォームは nil に評価されます。Common Lisp では `proclaim` は引数が評価される関数ですが、ここではマクロとして分類されるため引数も評価されません（`#'proclaim` もサポートされません）。この点は Common Lisp からの逸脱です。

```lisp
(proclaim '(special *state*)) ; => NIL
```
