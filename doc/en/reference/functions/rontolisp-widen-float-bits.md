# rontolisp:widen-float-bits

`(rontolisp:widen-float-bits bits format dst &key (start 0))`

Widens a packed `(unsigned-byte 16)` vector of `bits` -- `f16` or `bfloat16` bit
patterns, chosen by `format` (`:float16` or `:bfloat16`) -- into `dst`, a packed
float array (`single-float` or `double-float`, any rank), row-major from flat
index `start`. Returns `dst`.

This is the bulk form of `rontolisp:bits-float16`/`rontolisp:bits-bfloat16`: a
published checkpoint's tensors arrive as a whole vector of sixteen-bit patterns,
never one element at a time, and widening them one call at a time would cost a
function-call boundary per element. Every element gets exactly the scalar
primitive's answer -- widening is total and exact for both formats, so this
never rounds.

```lisp
(let* ((bits (make-array 3 :element-type '(unsigned-byte 16)
                          :initial-contents (list (rontolisp:float16-bits 1.0)
                                                   (rontolisp:float16-bits -2.5)
                                                   (rontolisp:float16-bits 100.0))))
       (dst (make-array 5 :element-type 'single-float :initial-element 0.0)))
  (rontolisp:widen-float-bits bits :float16 dst :start 2)
  (list (aref dst 0) (aref dst 1) (aref dst 2) (aref dst 3) (aref dst 4)))
; => (0.0 0.0 1.0 -2.5 100.0)
```

`:start` is where a chunked read lands its next slice inside a whole tensor's
destination -- see [checkpoint:stage-float-bits](checkpoint-stage-float-bits.md)
for the pattern. Works on the interpreter, the JVM and both WASM backends;
`--no-gc` has no packed float array model and refuses at compile time.
`rontolisp:narrow-float-bits` is the inverse direction.
