# rontolisp:narrow-float-bits

`(rontolisp:narrow-float-bits src format dst &key (start 0))`

Narrows a packed float array `src` (`single-float` or `double-float`, any rank)
into `dst`, a packed `(unsigned-byte 16)` vector of bit patterns -- `f16` or
`bfloat16`, chosen by `format` (`:float16` or `:bfloat16`) -- row-major from
`src`'s flat index `0` into `dst` starting at flat index `start`. Returns `dst`.

This is the bulk form of `rontolisp:float16-bits`/`rontolisp:bfloat16-bits`, the
inverse of `rontolisp:widen-float-bits`: writing a checkpoint back out at a
narrower width. Every element rounds exactly as the matching scalar primitive
would -- **nearest, ties to even** for both formats.

```lisp
(let* ((src (make-array 3 :element-type 'single-float
                         :initial-contents (list 1.0 -2.5 100.0)))
       (dst (make-array 3 :element-type '(unsigned-byte 16))))
  (rontolisp:narrow-float-bits src :bfloat16 dst)
  (list (aref dst 0) (aref dst 1) (aref dst 2)))
; => (16256 49184 17096)
```

Works on the interpreter, the JVM and both WASM backends; `--no-gc` has no
packed float array model and refuses at compile time.
