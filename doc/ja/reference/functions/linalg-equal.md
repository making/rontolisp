# linalg:equal

`(linalg:equal a b)`

要素ごとの数値等値を 0.0/1.0 のマスクとして返します (numpy の `==`)。どちらの被演算子もスカラー可で、[`linalg:add`](linalg-add.md) に記載のとおり numpy の規則でブロードキャストされます。numpy が boolean 配列で index する場面では、このマスクを掛け算してください。配列全体で 1 つの真偽値が必要な場合は [`linalg:array-equal`](linalg-array-equal.md) を使ってください。他の比較は [`linalg:greater`](linalg-greater.md)・[`linalg:greater-equal`](linalg-greater-equal.md)・[`linalg:less`](linalg-less.md)・[`linalg:less-equal`](linalg-less-equal.md) です。

```lisp
(linalg:equal #(1 5 3) #(2 5 1)) ; => #d(0.0 1.0 0.0)
```
