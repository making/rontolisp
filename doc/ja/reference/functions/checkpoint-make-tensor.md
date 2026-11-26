# checkpoint:make-tensor

`(checkpoint:make-tensor shape element-type)`

`shape`（次元のリスト、またはベクタなら 1 つの整数）と `element-type`（`single-float`、`double-float`、またはインタプリタと JVM では `bfloat16`）のパックされた浮動小数点配列を、ゼロで埋めて返します。素の `make-array` と違い、得た配列がパックされていることを**検証**します。`make-array :element-type` は認識しない要素型に対してエラーを出さず boxed な汎用配列を返すため、そこに読み込んだチェックポイントは、ずっと後になって遅く間違った出力としてしか現れません。リーダーが使う確保経路はこれ 1 つです。

```lisp
(checkpoint:make-tensor 3 'single-float)
; => #f(0.0 0.0 0.0)
```

```lisp
(array-dimensions (checkpoint:make-tensor '(2 3) 'double-float))
; => (2 3)
```
