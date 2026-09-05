# rontolisp:dequantize

`(rontolisp:dequantize matrix element-type)`

量子化行列（[`rontolisp:quantize`](rontolisp-quantize.md)、GGUF の Q8_0 テンソル）を、
同じ次元で `element-type`（`single-float`、`double-float`、`bfloat16`）の新しい
パックされた浮動小数点配列に展開します。各要素は `q * d` で、`single-float` では
厳密です（8 ビットの量子と binary16 のスケールの積）。`bfloat16` のコピーは最近接
偶数丸めで狭めます。

```lisp
(let ((v (make-array 32 :element-type 'single-float :initial-element 0.0)))
  (dotimes (j 32) (setf (aref v j) (- j 16)))
  (setf (aref v 0) 127.0)
  (let ((m (rontolisp:quantize v 'q8-0)))
    (list (array-element-type (rontolisp:dequantize m 'double-float))
          (aref (rontolisp:dequantize m 'single-float) 1)
          (aref (rontolisp:dequantize m 'bfloat16) 31))))
; => (DOUBLE-FLOAT -15.0 15.0)
```

量子化行列が直接受け付けないパック浮動小数点の操作へは、すべてここを通ります。
`vec:matvec` はそのまま読み、それ以外は逆量子化したコピーを読みます。コピーは
1 要素 1 バイトではなく 4 バイトなので、チェックポイント全体ではなく必要なテンソル
だけを逆量子化してください。

インタプリタと JVM のみ。両方の WASM バックエンドはコンパイル時に拒否します。
