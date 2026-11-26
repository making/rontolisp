# rontolisp:quantized-rows

`(rontolisp:quantized-rows matrix rows)`

Gathers the rows of a quantized matrix
([`rontolisp:quantize`](rontolisp-quantize.md)) named by the list of row indexes
`rows` into a fresh rank-2 matrix of the same format: row `i` of the result is
row `(nth i rows)` of `matrix`. A row of a Q8_0 matrix is whole 34-byte blocks
(`cols / 32` of them), so the gather MOVES THE BLOCKS AS THEY ARE -- one array
copy a row, no dequantizing and no re-quantizing, and the result's bytes are the
source's. It is this type's `subseq` and [`linalg:take-rows`](linalg-take-rows.md)
in one; a contiguous range is a range of indexes.

```lisp
(let* ((w (make-array '(4 32) :element-type 'single-float :initial-element 1.0))
       (m (rontolisp:quantize w 'q8-0))
       (g (rontolisp:quantized-rows m '(3 1))))
  (list g (array-dimensions g) (= (aref g 0 7) (aref m 3 7)) (= (aref g 1 7) (aref m 1 7))))
; => (#<quantized-matrix q8-0 (2 32)> (2 32) T T)
```

The source may be rank 1 (a matrix of one row); the result is always rank 2, so
`(rontolisp:quantized-rows m '())` is a matrix of no rows. An index outside the
source signals, and so does anything but a list of integers.

That is how a program splits a packed weight matrix -- a `query | gate` q_proj
whose halves interleave head by head, say -- while keeping it quantized. The
alternative, dequantizing and quantizing back, is exact for Q8_0 but cannot be
NAMED by a program that also compiles to WASM.

Interpreter and JVM only. On the WASM backends the call signals at run time, so
a reader's Q8_0 arm compiles everywhere and refuses only when reached.
