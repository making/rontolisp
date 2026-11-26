# rontolisp:quantized-matrix-p

`(rontolisp:quantized-matrix-p object)`

`t` when `object` is a quantized matrix ([`rontolisp:quantize`](rontolisp-quantize.md)),
`nil` for anything else -- a packed float array included, since a quantized
matrix is not an array. `(typep object 'rontolisp:quantized-matrix)` and
`typecase` answer the same question through the type name.

```lisp
(let ((v (make-array 32 :element-type 'single-float :initial-element 1.0)))
  (list (rontolisp:quantized-matrix-p (rontolisp:quantize v 'q8-0))
        (rontolisp:quantized-matrix-p v)
        (typep (rontolisp:quantize v 'q8-0) 'rontolisp:quantized-matrix)
        (type-of (rontolisp:quantize v 'q8-0))))
; => (T NIL T QUANTIZED-MATRIX)
```

Works on every backend: on the two WASM backends no quantized matrix can exist,
so the predicate is `nil` there.
