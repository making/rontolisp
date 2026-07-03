# linalg:array-equal

`(linalg:array-equal a b)`

`a` と `b` が同じ形状で、要素が数値として等しい場合に `t` を、そうでなければ `nil` を返します。数値は `=` で比較されるため、`1` と `1.0` は等しいと判定されます（numpy の `array_equal` と同様です）。linalg の結果を比較するにはこの関数を使ってください。配列そのものは同一性 (`eq`) でしか比較されないため、別々に構築した 2 つの配列が `equal` になることはありません。

```lisp
(linalg:array-equal (linalg:eye 2) (linalg:from-list '((1 0) (0 1)))) ; => t
(linalg:array-equal #(1 2) #(1 2 3)) ; => nil
```
