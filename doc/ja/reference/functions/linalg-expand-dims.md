# linalg:expand-dims

`(linalg:expand-dims array axis)`

`array` の `axis` の位置に extent 1 の軸を挿入したコピーを返します (numpy の `np.expand_dims`、torch の `unsqueeze`)。負の `axis` は*結果*の末尾から数えるので、`-1` は新しい軸を末尾に追加します。行優先の要素の並びは変わらず形状だけが変わり、要素幅は入力に従います。逆操作は [`linalg:squeeze`](linalg-squeeze.md) です。

```lisp
(linalg:expand-dims #(1 2 3) 0)  ; => #d((1.0 2.0 3.0))
(linalg:expand-dims #(1 2 3) -1) ; => #d((1.0) (2.0) (3.0))
```
