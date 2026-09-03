# rontolisp:bits-float16

`(rontolisp:bits-float16 integer)`

IEEE 754 binary16 (`f16`) ビットパターンが表す浮動小数点数を返します。引数の下位
16 ビットのみを読みます。65536 パターンのすべてが浮動小数点数にデコードされます
(無限大と NaN を含む) が、`rontolisp:float16-bits` との往復は NaN のペイロードに
関しては必ずしも恒等写像ではありません。このプリミティブが基づいている JDK の
`Float.float16ToFloat`/`Float.floatToFloat16` の対は、途中でシグナリング NaN を
静音化することがあります。`rontolisp:bits-bfloat16`/`rontolisp:bfloat16-bits` には
そのような欠落はありません。

```lisp
(list (rontolisp:bits-float16 15360)
      (rontolisp:bits-float16 (rontolisp:float16-bits 0.1))) ; => (1.0 0.0999755859375)
```

2 番目の値が要点です。`0.1` は `f16` の値ではなく、この往復はこの幅がどの浮動
小数点数を保持できるかを正確に示します。
