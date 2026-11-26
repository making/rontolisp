# linalg:greater

`(linalg:greater a b)`

要素ごとの `a > b` を 0.0/1.0 のマスクとして返します (numpy の `>`)。どちらの被演算子もスカラー可で、[`linalg:add`](linalg-add.md) に記載のとおり numpy の規則でブロードキャストされます。relu の勾配や dropout のマスクなど、numpy が boolean index する場面ではこのマスクを掛け算します。他の比較は [`linalg:greater-equal`](linalg-greater-equal.md)・[`linalg:less`](linalg-less.md)・[`linalg:less-equal`](linalg-less-equal.md)・[`linalg:equal`](linalg-equal.md) です。

```lisp
(linalg:greater #(1 5 3) 2) ; => #d(0.0 1.0 1.0)
```
