# torch:index-select

`(torch:index-select a idx)`

微分可能な軸 0 のスライス選択 (`linalg:take-rows`)、すなわち埋め込み参照です。各 `i` についてテーブルの行 `idx[i]` を返し、ランク 1 以上の任意の配列で動き、同じインデックスが重複しても構いません。backward は各出力スラブの勾配を元の行に散布加算するため、2 回選ばれた行は両方の寄与を蓄積します (埋め込み共有のケース)。

```lisp
(torch:data (torch:index-select (torch:tensor '((1.0 2.0) (3.0 4.0))) #(1 0 1)))
; => #d((3.0 4.0) (1.0 2.0) (3.0 4.0))
```
