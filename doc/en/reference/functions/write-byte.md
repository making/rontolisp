# write-byte

`(write-byte byte stream)`

Writes one byte -- an integer between 0 and 255 -- to a binary output stream (a stream opened with `:direction :output :element-type '(unsigned-byte 8)`) and returns the byte. Works in all three backends. The byte is written raw, with no newline or other framing added.

Because it touches the filesystem, `write-byte` is shown here statically rather than as a runnable example:

```console
(with-open-file (out "data.bin" :direction :output :element-type '(unsigned-byte 8))
  (write-byte 137 out)  ; => 137
  (write-byte 80 out)
  (write-byte 78 out)
  (write-byte 71 out))
```

This writes the four bytes `89 50 4E 47` (the start of a PNG signature) to `data.bin`. The interpreter and JVM signal an error for a value outside 0-255.
