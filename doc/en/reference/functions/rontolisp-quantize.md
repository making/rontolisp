# rontolisp:quantize

`(rontolisp:quantize array format)`

Quantizes a packed float array -- `single-float`, `double-float` or `bfloat16`,
rank 1 or 2, its last dimension a multiple of 32 -- into a **quantized
matrix** in `format`. The one format is `q8-0`, ggml's `Q8_0`: blocks of 32
elements, each block one binary16 scale `d` and 32 signed 8-bit quants, so a
value is `q * d` and a matrix costs 1.0625 bytes an element. The arithmetic is
ggml's own `quantize_row_q8_0_ref` -- the f32 absmax of each block, `d = amax /
127`, every quant `round(x / d)` with ties away from zero, `d` rounded to
binary16 -- so the matrix holds the bytes `llama-quantize` writes for the same
values, and `write-sequence` of it is a Q8_0 tensor `llama.cpp` reads back.

A quantized matrix is its own type, not an array: `aref` and `row-major-aref`
answer the dequantized `q * d` as a double, `(setf aref)` signals (an element
has no slot of its own -- writing one would re-quantize its block),
`array-dimensions` / `array-rank` / `array-total-size` work,
`array-element-type` answers the format symbol `q8-0`, `arrayp` is `nil`, and
[`rontolisp:quantized-matrix-p`](rontolisp-quantized-matrix-p.md) and `(typep x
'rontolisp:quantized-matrix)` recognize it. It prints as
`#<quantized-matrix q8-0 (rows cols)>`; there is no literal syntax.

```lisp
(let ((w (make-array '(2 32) :element-type 'single-float :initial-element 0.0)))
  (dotimes (j 32)
    (setf (aref w 0 j) (* 4.0 j))
    (setf (aref w 1 j) (- 127.0 (* 4 j))))
  (setf (aref w 0 31) 127.0)
  (let ((m (rontolisp:quantize w 'q8-0)))
    (list m (array-dimensions m) (array-element-type m) (aref m 0 4) (aref m 1 1)
          (rontolisp:quantized-matrix-p m) (arrayp m))))
; => (#<quantized-matrix q8-0 (2 32)> (2 32) Q8-0 16.0 123.0 T NIL)
```

Both rows above have an absmax of 127, so `d` is exactly 1 and every value
survives; in general a value moves by at most half a quant, `amax / 254`.

[`vec:matvec`](../../guides/simd-acceleration.md) and `vec:matvec-into` take a
quantized matrix as the matrix, against an `#f` or `#d` vector, and compute
ggml's integer-dot shape: the activation quantized to int8 per block of 32, four
exact integer lane sums per block, one double multiply-add per lane into four
accumulators. That is the same value bit for bit on the interpreter and the JVM,
with and without `--simd` and `--parallel`. Every other `vec:` and `linalg:` operation wants a packed float
array -- [`rontolisp:dequantize`](rontolisp-dequantize.md) first (`linalg:row`
is the exception: it reads a row of a quantized matrix straight into an `#f`
vector). The quantized product is a quantization error away from the f32 one
(about 8e-3 relative on published weights), not a rounding error: the number a
Q8_0 model produces is the number `llama.cpp` produces from the same file, not
the number the BF16 file produces.

Interpreter and JVM only. Both WASM backends refuse `rontolisp:quantize` and
`rontolisp:dequantize` at compile time; `--gpu` and `--blas` decline the type
and the lane kernel or the scalar defun answers. `gguf:read` builds one from a
Q8_0 tensor without going through this function.
