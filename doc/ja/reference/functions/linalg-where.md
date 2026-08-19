# linalg:where

`(linalg:where mask x y)`

要素ごとの選択です (numpy の `np.where`)。`mask` が**非ゼロ**の位置では `x` の要素を、ゼロの位置では `y` の要素を返します。[`linalg:greater`](linalg-greater.md) などが返す 0.0/1.0 のマスクをそのまま選択に使えるので、マスクを掛け算する回り道が不要になります。これは重要で、掛け算は無限大のオペランドを `NaN` にしてしまうのに対し選択はそうならないため、`-infinity` のアテンションマスクが [`linalg:softmax`](linalg-softmax.md) にちょうど 0 の重みとして届きます。3 つの引数はいずれもスカラーでも配列でもよく、numpy の規則でブロードキャストされます。結果は `x` が配列ならその要素幅を、そうでなければ `y` の要素幅を保ちます。

```lisp
(linalg:where (linalg:greater #(1 5 3) 2) #(1 5 3) 0) ; => #d(0.0 5.0 3.0)
(linalg:where #(1 0 1) 10 20)                         ; => #d(10.0 20.0 10.0)
```
