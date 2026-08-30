# file-length

`(file-length stream)`

The byte length of the file a **file** stream is open on, or `nil` when it cannot be determined. Every other stream answers `nil`: a string stream, a socket, one of the standard streams, and a handle that has already been closed. An output stream is flushed first, so the answer counts what has been written rather than what happens to have reached the disk.

**All four backends answer for real.** The interpreter and the JVM stat the path the stream was opened with; both WASM backends stat the descriptor itself (Preview 1 through `fd_filestat_get`, the component through `wasi:filesystem`'s `descriptor.stat`), and answer `nil` only for what genuinely has no length -- which is the same set the other two answer `nil` for. Anything the host does not report as a regular file is `nil` as well.

```lisp
(with-input-from-string (s "abc")
  (file-length s)) ; => NIL
```

```console
(with-open-file (in "data.txt")
  (print (file-length in)))
```
