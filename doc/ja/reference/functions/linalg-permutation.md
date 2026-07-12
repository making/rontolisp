# linalg:permutation

`(linalg:permutation n)`

整数 0..n-1 を Fisher-Yates でシャッフルした packed double ベクタを返します (整数を渡したときの numpy `np.random.permutation`)。エポックごとに訓練データの順序を入れ替えるイディオムで、結果はそのまま [`linalg:take-rows`](linalg-take-rows.md) に渡せます。再現可能な列にするには、先に [`linalg:seed`](linalg-seed.md) を呼んでください。

```lisp
(linalg:seed 9) ; => 9
(linalg:permutation 10) ; => #d(4.0 5.0 6.0 2.0 9.0 7.0 1.0 0.0 8.0 3.0)
```
