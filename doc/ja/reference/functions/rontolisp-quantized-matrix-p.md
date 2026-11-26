# rontolisp:quantized-matrix-p

`(rontolisp:quantized-matrix-p object)`

`object` が量子化行列（[`rontolisp:quantize`](rontolisp-quantize.md)）なら `t`、
それ以外は `nil` です。量子化行列は配列ではないので、パックされた浮動小数点配列に
対しても `nil` です。`(typep object 'rontolisp:quantized-matrix)` と `typecase` は
型名を通して同じ問いに答えます。

```lisp
(let ((v (make-array 32 :element-type 'single-float :initial-element 1.0)))
  (list (rontolisp:quantized-matrix-p (rontolisp:quantize v 'q8-0))
        (rontolisp:quantized-matrix-p v)
        (typep (rontolisp:quantize v 'q8-0) 'rontolisp:quantized-matrix)
        (type-of (rontolisp:quantize v 'q8-0))))
; => (T NIL T QUANTIZED-MATRIX)
```

すべてのバックエンドで動きます。両方の WASM バックエンドには量子化行列が存在
し得ないので、そこでは `nil` です。
