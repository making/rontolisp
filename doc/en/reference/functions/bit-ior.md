# bit-ior

`(bit-ior bit-array1 bit-array2 &optional result-bit-array)`

Element-wise inclusive-or of two bit arrays of the same dimensions, answered as a bit vector. Both inputs must be bit arrays -- a `#*` literal or a [`make-array`](make-array.md) result with `:element-type 'bit`, of any rank -- with equal dimensions; anything else signals an error.

When `result-bit-array` is omitted or `nil`, a fresh bit array is created. When it is `t`, `bit-array1` is reused destructively. Otherwise it must be a bit array of the same dimensions; it is written into and answered. See [`bit-vector-p`](bit-vector-p.md) for what counts as a bit array.

```lisp
(bit-ior #*0110 #*1100) ; => #(1 1 1 0)
```
