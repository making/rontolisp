# rontolisp:float16-bits

`(rontolisp:float16-bits real)`

実数の IEEE 754 binary16 (`f16`) ビットパターンを 0〜65535 の整数として返します。`bfloat16` とは異なり、`f16` は `f32` とは指数部・仮数部の配分そのものが違います（指数部 5 ビット、仮数部 10 ビットであり、8 ビットと 7 ビットではありません）。したがって幅と引き換えにするのは精度だけでなく範囲でもあり、指数の範囲を超える値は丸められた有限数ではなく無限大に拡張されます。

丸めは `rontolisp:bfloat16-bits` と同じく **最近接偶数丸め (round to nearest, ties to even)** です。以下の 2 つのちょうど中間の値は互いに逆方向に丸められます。

```lisp
(list (rontolisp:float16-bits 1.0)
      (rontolisp:float16-bits -2.5)
      (rontolisp:float16-bits 1.00048828125)
      (rontolisp:float16-bits 1.00146484375)) ; => (15360 49408 15360 15362)
```

`rontolisp:bits-float16` がパターンを値に戻します。16 ビットはどのバックエンドでも
fixnum に収まるので、`float-features:single-float-bits` などが扱うより幅の広い IEEE
の対とは異なり、この対は多倍長整数のモデルを必要とせず `float-features:` ではなく
`rontolisp:` のプリミティブです。テンソル丸ごとが実際に届く形であるバルク版は
`rontolisp:widen-float-bits` と `rontolisp:narrow-float-bits` を参照してください。
