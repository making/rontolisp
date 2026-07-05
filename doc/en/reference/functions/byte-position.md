# byte-position

`(byte-position bytespec)`

Returns the position (starting bit offset, 0 = least significant bit) of the byte specifier `bytespec` built by [`byte`](byte.md).

```lisp
(byte-position (byte 8 3)) ; => 3
```
