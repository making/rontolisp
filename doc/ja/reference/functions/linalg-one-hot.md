# linalg:one-hot

`(linalg:one-hot indices n &optional element-type)`

`(length indices)` 行 `n` 列の one-hot 行列を返します。行 `i` は列 `indices[i]` (整数に truncate) に 1.0 を持ち、他の要素は 0.0 です。デフォルトは packed double-float で、`element-type` に `'single-float` を渡すと `#f` になります。分類ラベルのベクタを行列に展開するイディオムで、逆向き (行ごとに 1 要素を取り出す) は [`linalg:gather`](linalg-gather.md) です。

```lisp
(linalg:one-hot #(1 0 2) 3) ; => #d((0.0 1.0 0.0) (1.0 0.0 0.0) (0.0 0.0 1.0))
```
