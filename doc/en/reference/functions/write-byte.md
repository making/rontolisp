# write-byte

`(write-byte byte stream)`

Writes one byte -- an integer between 0 and 255 -- to a binary output stream (a stream opened with `:direction :output :element-type '(unsigned-byte 8)`) and returns the byte. Works in all four backends. The byte is written raw, with no newline or other framing added.

`stream` takes the same designators every other stream operation takes: `t` is the process standard output, `nil` means the current `*standard-output*` (which holds `t` unless you bind it), and `*error-output*` is standard error. `(write-byte b *standard-output*)` therefore puts raw octets on standard output, in order with anything `princ` and `format` write there.

Because it touches the filesystem, `write-byte` is shown here statically rather than as a runnable example:

```console
(with-open-file (out "data.bin" :direction :output :element-type '(unsigned-byte 8))
  (write-byte 137 out)  ; => 137
  (write-byte 80 out)
  (write-byte 78 out)
  (write-byte 71 out))

(write-byte 137 *standard-output*)  ; one raw octet on stdout
```

This writes the four bytes `89 50 4E 47` (the start of a PNG signature) to `data.bin`. The interpreter and JVM signal an error for a value outside 0-255.
