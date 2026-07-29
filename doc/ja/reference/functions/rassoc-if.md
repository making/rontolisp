# rassoc-if

`(rassoc-if predicate alist)`

連想リストを検索し、cdr が `predicate` を満たす最初のペアを返します。満たすものがなければ `nil` を返します。これは各ペアの car を判定する `assoc-if` の対になるもので、`rassoc` の述語版です。各ペアは `(funcall predicate (cdr pair))` で判定され、リスト中のコンスでない要素はスキップされます。返されるペアは連想リストと構造を共有します。

```lisp
(rassoc-if #'oddp '((a . 2) (b . 3))) ; => (B . 3)
```

```lisp
(rassoc-if #'consp '((1 . 2) (3 4 . 5))) ; => (3 4 . 5)
```
