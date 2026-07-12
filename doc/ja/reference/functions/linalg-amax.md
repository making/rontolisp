# linalg:amax

`(linalg:amax array &optional axis keepdims)`

配列全体 (`axis` なし)、または整数の `axis` に沿った最大の要素を返します。省略可能な `axis` / `keepdims` は [`linalg:sum`](linalg-sum.md) と同じ規則です (負の `axis` は末尾から数え、還元した軸は結果から除去 -- `keepdims` で長さ 1 の軸として保持)。厳密比較の fold で定義されるため、同値の場合は最初の要素が採用され、`NaN` がシードを置き換えることはありません。空の配列や空の軸はエラーを通知します。最大要素の*インデックス*には [`linalg:argmax`](linalg-argmax.md) を使ってください。最小の要素に対する対応物は [`linalg:amin`](linalg-amin.md) です。

```lisp
(linalg:amax #2A((1 9) (3 4))) ; => 9
(linalg:amax #2A((1 9) (3 4)) 0) ; => #d(3.0 9.0)
(linalg:amax #2A((1 9) (3 4)) 1 t) ; => #d((9.0) (4.0))
```
