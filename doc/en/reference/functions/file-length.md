# file-length

`(file-length stream)`

The byte length of the file a **file** stream is open on, or `nil` when it cannot be determined. Every other stream answers `nil`: a string stream, a socket, one of the standard streams, and a handle that has already been closed. An output stream is flushed first, so the answer counts what has been written rather than what happens to have reached the disk.

**Both WASM backends always answer `nil`**, file streams included: no WASI `filestat` call is imported there. `nil` is exactly Common Lisp's answer for "the length cannot be determined", so a portable caller takes its unknown-length fallback rather than failing. The interpreter and the JVM answer for real.

```lisp
(with-input-from-string (s "abc")
  (file-length s)) ; => NIL
```

```console
(with-open-file (in "data.txt")
  (print (file-length in)))
```
