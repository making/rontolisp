# read-byte

`(read-byte stream &optional eof-error-p eof-value)`

Reads one byte from a binary input stream -- a stream opened with `:element-type '(unsigned-byte 8)` -- and returns it as an integer between 0 and 255. At end of file it signals an `end-of-file` condition by default (catchable as `end-of-file`, or as `error`); passing `nil` as `eof-error-p` makes it return `eof-value` (default `nil`) instead. Works in all four backends. Bytes pass through raw: values such as 0 (NUL), 10 (LF) and 34 (`"`) are not interpreted.

`stream` takes the same designators every other stream operation takes: `t` is the process standard input, and `nil` means the current `*standard-input*` -- which holds `t` unless you bind it. `(read-byte *standard-input*)` therefore reads raw octets from standard input, which is how a byte-oriented filter reads its input.

Because it touches the filesystem, `read-byte` is shown here statically rather than as a runnable example:

```console
(with-open-file (in "data.bin" :element-type '(unsigned-byte 8))
  (read-byte in)         ; => 137
  (read-byte in nil nil)) ; => nil at end of file

(read-byte *standard-input* nil nil) ; => the next octet of stdin, nil at EOF
```

The first call returns the next byte of `data.bin`; the second form reads until end of file without signalling, returning `nil` when the bytes are exhausted -- the usual loop-termination test when reading a whole file.

Do not mix `read-byte` with `read-line` / `read-char` on the same stream: the character reads buffer ahead, so bytes a following `read-byte` owes you may already have been consumed.
