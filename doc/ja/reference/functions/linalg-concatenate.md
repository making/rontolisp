# linalg:concatenate

`(linalg:concatenate arrays &key axis)`

リスト `arrays` に含まれる配列を**既存の**軸に沿って連結します (numpy の `np.concatenate`、torch の `cat`)。`:axis` のデフォルトは 0 で、負の値は末尾から数えます。すべての入力は同じ rank を持ち、その軸以外のすべての軸の extent が一致していなければなりません。連結される軸の extent はそれらの合計になります。結果は最初の入力の要素幅を持つ新しい配列です。**新しい**軸に沿って連結するには [`linalg:stack`](linalg-stack.md) を使ってください。

```lisp
(linalg:concatenate (list #(1 2) #(3)))                   ; => #d(1.0 2.0 3.0)
(linalg:concatenate (list #2A((1 2)) #2A((3 4))))         ; => #d((1.0 2.0) (3.0 4.0))
(linalg:concatenate (list #2A((1 2)) #2A((3 4))) :axis 1) ; => #d((1.0 2.0 3.0 4.0))
```
