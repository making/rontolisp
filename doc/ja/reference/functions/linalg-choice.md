# linalg:choice

`(linalg:choice n size)`

[0, n) の一様なインデックスを `size` 個、復元抽出 (同じインデックスが複数回現れうる) で引いた packed double ベクタを返します (整数引数を渡したときの numpy `np.random.choice` のデフォルト動作)。訓練データからミニバッチを抽出するイディオムで、結果はそのまま [`linalg:take-rows`](linalg-take-rows.md) に渡せます。再現可能な列にするには、先に [`linalg:seed`](linalg-seed.md) を呼んでください。

```lisp
(linalg:seed 42) ; => 42
(linalg:choice 60000 4) ; => #d(26833.0 11120.0 29256.0 22347.0)
```
