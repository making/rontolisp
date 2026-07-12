# linalg:zeros-like

`(linalg:zeros-like array)`

入力と同じ形状かつ同じ要素幅 (`#d` は `#d`、`#f` は `#f`) のゼロ配列を返します (numpy の `np.zeros_like`)。形状を明示して作るには [`linalg:zeros`](linalg-zeros.md) を使ってください。

```lisp
(linalg:zeros-like #2A((1 2) (3 4))) ; => #d((0.0 0.0) (0.0 0.0))
```
