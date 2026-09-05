# rontolisp:dequantize

`(rontolisp:dequantize matrix element-type)`

Expands a quantized matrix ([`rontolisp:quantize`](rontolisp-quantize.md), a
GGUF's Q8_0 tensor) into a fresh packed float array of the same dimensions and
of `element-type` -- `single-float`, `double-float` or `bfloat16`. Every element
is `q * d`, which is exact in `single-float` (an 8-bit quant times a binary16
scale); the `bfloat16` copy narrows it to nearest even.

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

This is the way onto every packed-float operation a quantized matrix does not
take directly: `vec:matvec` reads it as it is, everything else reads the
dequantized copy. The copy is what the arithmetic ran over, at four bytes an
element instead of one -- dequantize the tensors you need, not the checkpoint.

Interpreter and JVM only; both WASM backends refuse it at compile time.
