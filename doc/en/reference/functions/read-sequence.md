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
