# linalg:uniform

`(linalg:uniform lo hi shape &optional element-type)`

[lo, hi) の一様乱数で埋めた配列を返します (numpy の `np.random.uniform` 相当ですが、shape designator は必須です。指定方法は [`linalg:rand`](linalg-rand.md) と同じ)。各要素は [`linalg:rand`](linalg-rand.md) の draw を `lo + (hi - lo) * u` にスケールしたものです。再現可能な列にするには、先に [`linalg:seed`](linalg-seed.md) を呼んでください。

```lisp
(linalg:seed 7) ; => 7
(linalg:emap (lambda (x) (truncate x)) (linalg:uniform 10 20 4)) ; => #d(15.0 15.0 18.0 12.0)
```
