# linalg:randn

`(linalg:randn shape &optional element-type)`

標準正規分布の乱数で埋めた配列を返します (numpy の `np.random.randn` 相当。shape designator と `element-type` は [`linalg:rand`](linalg-rand.md) と同じ)。ガウス乱数は Irwin-Hall (一様乱数 12 個の和から 6 を引く) で生成します。Box-Muller ではなく Irwin-Hall なのは、WASM の `log` / `cos` が多項式近似でバックエンド間の bit 一致が壊れる一方、`+` / `-` では壊れないためです。裾は ±6σ でクリップされます -- 重みの初期化には十分ですが、`np.random.randn` と分布が厳密に一致するわけではありません。

```lisp
(linalg:seed 42) ; => 42
(linalg:emap (lambda (x) (truncate (* 1024 x))) (linalg:randn 4)) ; => #d(164.0 -469.0 -1782.0 -1292.0)
```
