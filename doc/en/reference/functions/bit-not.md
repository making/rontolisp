# bit-not

`(bit-not bit-array &optional result-bit-array)`

Element-wise negation of one bit array, answered as a bit vector. The input must be a bit array -- a `#*` literal or a [`make-array`](make-array.md) result with `:element-type 'bit`, of any rank; anything else signals an error.

When `result-bit-array` is omitted or `nil`, a fresh bit array is created. When it is `t`, `bit-array` is reused destructively. Otherwise it must be a bit array of the same dimensions; it is written into and answered. See [`bit-vector-p`](bit-vector-p.md) for what counts as a bit array.

```lisp
(bit-not #*0110) ; => #*1001
```
