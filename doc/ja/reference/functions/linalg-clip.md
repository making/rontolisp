# linalg:clip

`(linalg:clip a lo hi)`

すべての要素を区間 `[lo, hi]` に制限した新しい配列を返します（スカラー境界の numpy `np.clip`）。合成 `(linalg:minimum (linalg:maximum a lo) hi)` として定義され、その帰結が 2 つあります: `NaN` 要素は `lo` になり（最初の比較が偽なので境界が選ばれる）、境界が逆転している（`lo > hi`）場合はすべての要素が `hi` になります。[`--simd`](../../guides/simd-acceleration.md#accelerating-linalg) では [`linalg:maximum`](linalg-maximum.md) / [`linalg:minimum`](linalg-minimum.md) のカーネルに乗って高速化されます。

```lisp
(linalg:clip #d(-2.0 0.5 3.0) -1.0 1.0) ; => #d(-1.0 0.5 1.0)
```
