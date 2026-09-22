# stream-element-type

`(stream-element-type stream)`

The element type a stream carries. A binary **file** stream answers the integer type it was opened with, widened the way SBCL widens it: `(unsigned-byte 8)` for any one-octet unsigned type (`bit`, `(unsigned-byte 3)`, `(integer 100 200)`), `(unsigned-byte 16)` for `(unsigned-byte 9)` through `16`, `(signed-byte 32)` for `(signed-byte 20)`, and so on (see [`open`](open.md)). Every other stream -- a character file stream, a string stream, a socket, a standard stream -- answers `character`. Works in all four backends.

```lisp
(with-input-from-string (s "x")
  (stream-element-type s)) ; => CHARACTER
```

```console
(with-open-file (s "data.bin" :element-type '(unsigned-byte 12))
  (stream-element-type s)) ; => (UNSIGNED-BYTE 16)
```
