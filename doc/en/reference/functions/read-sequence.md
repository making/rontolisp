# read-sequence

`(read-sequence sequence stream &key start end)`

Fills `sequence` -- a one-dimensional array created with `make-array` -- with elements read from `stream`, and returns the index of the first element that was not filled (the fill position). Reading starts at index `:start` (default 0) and stops before index `:end` (default the array length) or at end of file, whichever comes first. The `:start`/`:end` keywords must be literal; their values may be arbitrary expressions.

The BUFFER decides which element is read: a character vector -- what `(make-array n :element-type 'character)` and `make-string` build -- is filled with characters from a text stream, and any other array is filled with bytes from a stream opened with `:element-type '(unsigned-byte 8)`. The element type may itself be computed, as in `(make-array n :element-type (stream-element-type s))`.

```lisp
(with-input-from-string (s "abcdef")
  (let ((buf (make-array 4 :element-type 'character)))
    (list (read-sequence buf s) buf))) ; => (4 "abcd")
```

Because it touches the filesystem, the binary form is shown here statically rather than as a runnable example:

```console
(let ((buf (make-array 8)))
  (with-open-file (in "data.bin" :element-type '(unsigned-byte 8))
    (read-sequence buf in))  ; => 4 when data.bin has 4 bytes
  (aref buf 0))              ; => the first byte
```

A return value smaller than the array length means the input ended early; elements at and beyond the fill position keep their previous values.

## Packed buffers: raw binary elements in bulk

When the buffer is a **packed** array -- a packed float array of any rank (`:element-type 'single-float` / `'double-float`, `#f(...)` / `#d(...)`) or a packed integer vector (`:element-type '(unsigned-byte 8)`, `16` or `32`) -- `read-sequence` reads its elements as **raw little-endian binary** from a binary stream, in one bulk transfer instead of a byte-at-a-time loop: a single-float is the 4 bytes of its IEEE-754 encoding, a double-float 8 bytes, an `(unsigned-byte 16)` 2 bytes, and so on. A rank-2 or rank-3 packed float array is filled in row-major order (`:start`/`:end` count elements, `:end` defaults to the total size). This is how a program loads a weight matrix, a numpy `.npy` payload or any C-struct dump: a `make-array` and one `read-sequence`, on every backend, at memcpy speed -- a llama2 checkpoint's 15 million floats load in about 0.2 s. A trailing partial element at end of file is not stored and not counted.

```console
(with-open-file (in "weights.bin" :element-type '(unsigned-byte 8))
  (let ((w (make-array '(288 288) :element-type 'single-float :initial-element 0.0)))
    (read-sequence w in)))  ; => 82944 -- 288*288 little-endian float32s, row-major
```

Only a general (boxed) array is filled through the `read-byte` loop above, so a program that wants integers larger than 255 as elements should use a packed `(unsigned-byte 16|32)` vector -- and a general vector still receives one byte per element.
