# linalg:stack

`(linalg:stack arrays &key axis)`

リスト `arrays` に含まれる配列を**新しい**軸に沿って連結します (numpy の `np.stack`)。すべての入力はまったく同じ形状でなければなりません。結果は `:axis` の位置 (デフォルト 0、負の値は*結果*の末尾から数えるので `-1` は末尾に追加します) に extent が `(length arrays)` の軸が 1 つ増えた配列になります。結果は最初の入力の要素幅を持つ新しい配列です。サンプルごとの配列のリストを 1 つのバッチ配列にするのがこの関数です。既存の軸に沿って連結するには [`linalg:concatenate`](linalg-concatenate.md) を使ってください。

```lisp
(linalg:stack (list #(1 2) #(3 4)))         ; => #d((1.0 2.0) (3.0 4.0))
(linalg:stack (list #(1 2) #(3 4)) :axis 1) ; => #d((1.0 3.0) (2.0 4.0))
```
