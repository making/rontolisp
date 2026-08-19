# torch:shape

`(torch:shape tensor)`

テンソルのデータの次元リスト (linalg の `shape`) を返します。スカラーテンソル (ランク 0) では `nil` です。

```lisp
(torch:shape (torch:tensor '((1 2 3) (4 5 6)))) ; => (2 3)
(torch:shape (torch:tensor 2.5))                ; => NIL
```
