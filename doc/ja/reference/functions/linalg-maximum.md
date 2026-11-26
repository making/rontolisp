# linalg:maximum

`(linalg:maximum a b)`

`a` と `b` の要素ごとに大きい方を集めた新しい配列を返します（numpy の `np.maximum`）。被演算子は [`linalg:add`](linalg-add.md) に記載のとおり numpy の規則でブロードキャストされます（スカラーは他方の shape に、shape の異なる配列同士は末尾の軸から揃えて）。IEEE の min/max プリミティブではなく厳密比較 `(if (> x y) x y)` で定義されるため、比較が偽になるときは常に第 2 被演算子が選ばれます — `-0.0` と `0.0` のタイ（第 2 被演算子を採る）や、順序付けできない `NaN` 比較（`NaN` 配列が第 1 引数なら `b` の要素、逆なら `NaN` が残る）も同じ規則です。すべてのバックエンドで同一の規則です。名前付き関数なので [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) で高速化されます。

```lisp
(linalg:maximum #d(1.0 5.0 3.0) #d(4.0 2.0 3.0)) ; => #d(4.0 5.0 3.0)
(linalg:maximum #d(1.0 5.0 3.0) 2.5) ; => #d(2.5 5.0 3.0)
```
