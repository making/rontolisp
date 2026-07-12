# linalg:amin

`(linalg:amin array &optional axis keepdims)`

配列全体 (`axis` なし)、または整数の `axis` に沿った最小の要素を返します。省略可能な `axis` / `keepdims` は [`linalg:sum`](linalg-sum.md) と同じ規則です (負の `axis` は末尾から数え、還元した軸は結果から除去 -- `keepdims` で長さ 1 の軸として保持)。厳密比較の fold で定義されるため、同値の場合は最初の要素が採用され、`NaN` がシードを置き換えることはありません。空の配列や空の軸はエラーを通知します。最小要素の*インデックス*には [`linalg:argmin`](linalg-argmin.md) を使ってください。最大の要素に対する対応物は [`linalg:amax`](linalg-amax.md) です。

```lisp
(linalg:amin #(5 2 8)) ; => 2
(linalg:amin #2A((1 9) (3 4)) 0) ; => #d(1.0 4.0)
```
