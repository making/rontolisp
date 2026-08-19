# write-sequence

`(write-sequence sequence stream &key start end)`

Writes the elements of `sequence` to `stream` and returns the sequence. Writing starts at index `:start` (default 0) and stops before index `:end` (default the sequence length). The `:start`/`:end` keywords must be literal; their values may be arbitrary expressions. Works in all three backends.

When `sequence` is a string, the bounded slice is written as characters (like `write-string`), so it works with a text output stream such as the one from `with-output-to-string`:

```lisp
(with-output-to-string (s) (write-sequence "abcd" s :start 1 :end 3)) ; => "bc"
```

When `sequence` is a one-dimensional array of integers between 0 and 255, it expands into a `write-byte` loop, so it requires a stream opened with `:direction :output :element-type '(unsigned-byte 8)`.

Because it touches the filesystem, `write-sequence` is shown here statically rather than as a runnable example:

```console
(let ((buf (make-array 4)))
  (setf (aref buf 0) 222) (setf (aref buf 1) 173)
  (setf (aref buf 2) 190) (setf (aref buf 3) 239)
  (with-open-file (out "data.bin" :direction :output :element-type '(unsigned-byte 8))
    (write-sequence buf out)))  ; => the array
```

This writes the four bytes `DE AD BE EF` to `data.bin`. Use `:start`/`:end` to write only a slice of the array.

## Packed buffers: raw binary elements in bulk

When `sequence` is a **packed** array -- a packed float array of any rank (`single-float` / `double-float`, `#f(...)` / `#d(...)`) or a packed integer vector (`(unsigned-byte 8|16|32)`) -- its elements go out as **raw little-endian binary** in one bulk transfer, exactly the bytes [`read-sequence`](read-sequence.md) reads back into the same kind of buffer: 4 bytes per single-float, 8 per double-float, 2 per `(unsigned-byte 16)`, and so on, a rank-n float array in row-major order (`:end` defaults to the total size). A `#f` matrix written this way is a `float32` file numpy or C reads directly.

```console
(with-open-file (out "weights.bin" :direction :output :element-type '(unsigned-byte 8))
  (write-sequence #f((1.0 2.0) (3.0 4.0)) out))  ; => the array; 16 bytes written
```
