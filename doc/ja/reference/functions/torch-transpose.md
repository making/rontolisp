# torch:transpose

`(torch:transpose a &optional axes)`

微分可能な転置です。`axes` なしでは行列の転置 (ベクトルはそのまま、`linalg:transpose` と同じ)、軸リストを渡すとランク n の軸の並べ替え (`out-dims[k] = dims[axes[k]]`、負の軸は末尾から数えます) です。backward は勾配に逆置換を適用します。

```lisp
(torch:data (torch:transpose (torch:tensor '((1.0 2.0) (3.0 4.0))))) ; => #d((1.0 3.0) (2.0 4.0))
```
