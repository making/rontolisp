# rontolisp:make-quantized-matrix

`(rontolisp:make-quantized-matrix format dims)`

Builds an all-zero quantized matrix ([`rontolisp:quantize`](rontolisp-quantize.md))
in `format` (`q8-0`) with the dimensions `dims` -- an integer for rank 1, or a
list of one or two integers, the last a multiple of 32. Its purpose is to be the
destination of a `read-sequence`: a quantized matrix's storage IS ggml's block
layout, 34 bytes for every 32 elements, so a Q8_0 tensor in a GGUF file is one
bulk transfer into it and `write-sequence` writes those same bytes back out.
That is how [`gguf:read`](gguf-read.md) loads a Q8_0 tensor.

```lisp
(let ((m (rontolisp:make-quantized-matrix 'q8-0 '(2 64))))
  (list m (array-total-size m) (aref m 1 63) (array-element-type m)))
; => (#<quantized-matrix q8-0 (2 64)> 128 0.0 Q8-0)
```

`read-sequence` and `write-sequence` count BYTES for this buffer (`:start` /
`:end` too), 34 per block; a full transfer of a `(rows cols)` matrix moves
`rows * cols / 32 * 34` of them.

Interpreter and JVM only. On the WASM backends the call signals at run time,
so a reader's Q8_0 arm compiles everywhere and refuses only when reached.
