# read-sequence

`(read-sequence sequence stream &key start end)`

Fills `sequence` -- a one-dimensional array created with `make-array` -- with bytes read from a binary input stream, and returns the index of the first element that was not filled (the fill position). Reading starts at index `:start` (default 0) and stops before index `:end` (default the array length) or at end of file, whichever comes first. The `:start`/`:end` keywords must be literal; their values may be arbitrary expressions. Works in all three backends; it expands into a `read-byte` loop, so it requires a stream opened with `:element-type '(unsigned-byte 8)`.

Because it touches the filesystem, `read-sequence` is shown here statically rather than as a runnable example:

```console
(let ((buf (make-array 8)))
  (with-open-file (in "data.bin" :element-type '(unsigned-byte 8))
    (read-sequence buf in))  ; => 4 when data.bin has 4 bytes
  (aref buf 0))              ; => the first byte
```

A return value smaller than the array length means the file ended early; elements at and beyond the fill position keep their previous values.
