# rontolisp:bits-bfloat16

`(rontolisp:bits-bfloat16 integer)`

`bfloat16` ビットパターンが表す浮動小数点数を返します。引数の下位 16 ビットのみを読みます。拡張は厳密かつ全域的なので、この関数は決して丸めません。すべてのパターンが浮動小数点数を表し、`rontolisp:bfloat16-bits` がそのまま元に戻します。

```lisp
(list (rontolisp:bits-bfloat16 16256)
      (rontolisp:bits-bfloat16 (rontolisp:bfloat16-bits 0.1))) ; => (1.0 0.10009765625)
```

2 番目の値が要点です。`0.1` は bfloat16 ではなく、この往復はこの幅がどの浮動小数点数を保持できるかを正確に示します。
