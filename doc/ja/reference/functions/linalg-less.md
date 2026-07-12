# linalg:less

`(linalg:less a b)`

要素ごとの `a < b` を 0.0/1.0 のマスクとして返します (numpy の `<`)。それ以外は [`linalg:greater`](linalg-greater.md) と同じ規則です (どちらの被演算子もスカラー可、numpy の規則でブロードキャスト、numpy が boolean index する場面ではマスクを掛け算)。

```lisp
(linalg:less #(1 5 3) 3) ; => #d(1.0 0.0 0.0)
```
