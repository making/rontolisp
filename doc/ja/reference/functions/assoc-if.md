# assoc-if

`(assoc-if predicate alist)`

連想リストを検索し、car が `predicate` を満たす最初のペアを返します。該当するものがなければ `nil` を返します。これは `assoc` の述語ベース版です。

```lisp
(assoc-if #'oddp '((2 a) (3 b))) ; => (3 B)
```
