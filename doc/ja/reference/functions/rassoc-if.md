# rassoc-if

`(rassoc-if predicate alist &key key)`

連想リストを検索し、cdr が `predicate` を満たす最初のペアを返します。満たすものがなければ `nil` を返します。これは各ペアの car を判定する `assoc-if` の対になるもので、`rassoc` の述語版です。各ペアは `(funcall predicate (cdr pair))` で判定され、リスト中のコンスでない要素はスキップされます。返されるペアは連想リストと構造を共有します。リストでない `alist` や、探索が末尾まで達したドットリストは `type-error` を通知します。

```lisp
(rassoc-if #'oddp '((a . 2) (b . 3))) ; => (B . 3)
```

```lisp
(rassoc-if #'consp '((1 . 2) (3 4 . 5))) ; => (3 4 . 5)
```

```lisp
(rassoc-if #'oddp '((a . 1) (b . 2)) :key #'1+) ; => (B . 2)
```
