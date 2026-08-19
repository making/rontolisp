# linalg:squeeze

`(linalg:squeeze array &key axis)`

`array` から extent 1 の軸を取り除いたコピーを返します (numpy の `np.squeeze`)。`:axis` なしではそのような軸をすべて取り除き、整数の `:axis` (またはそのリスト、負の値は末尾から数えます) を渡すとその軸だけを取り除きます。extent が 1 でない軸を指定するとエラーを通知します。*すべての*軸を取り除いた場合は要素そのものを返します。`linalg` には rank 0 の配列がなく、ここでの rank 0 は素の数値だからです。逆操作は [`linalg:expand-dims`](linalg-expand-dims.md) です。

```lisp
(linalg:squeeze #2A((1 2 3)))                         ; => #d(1.0 2.0 3.0)
(linalg:squeeze (linalg:expand-dims #(1 2) 0) :axis 0) ; => #d(1.0 2.0)
```
