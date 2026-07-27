# array-element-type

`(array-element-type array)`

Returns the array's element type. A packed float array answers `double-float` or `single-float`, and a packed unsigned-integer vector ([`make-array`](make-array.md) with a literal `:element-type '(unsigned-byte 8|16|32)` on a rank-1 array) answers its real `(unsigned-byte n)` specifier. For every general array the answer is the symbol `t` (other element types are accepted but not tracked). Provided for compatibility with portable code such as cl-utilities' `copy-array`.

```lisp
(array-element-type (make-array 3)) ; => T
(array-element-type (make-array 3 :element-type '(unsigned-byte 8))) ; => (UNSIGNED-BYTE 8)
```
