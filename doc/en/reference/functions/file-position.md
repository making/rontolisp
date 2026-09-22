# file-position

`(file-position stream [position])`

With one argument, the current byte position of a **binary** file stream — one opened with `:element-type '(unsigned-byte 8)` — or of a **bidirectional** one (`:direction :io`, or `:if-exists :overwrite`) of either element type. With two, it repositions the stream to `position` and answers `t`; the next read or write starts there. `position` may also be `:start` or `:end`.

Anything whose position cannot be determined answers `nil`, which is what Common Lisp prescribes for exactly that: a character file stream opened `:input` or `:output`, a string stream, a socket, one of the standard streams, and a handle that has already been closed. Portable callers guard the call with `ignore-errors` and take their non-seeking fallback path on `nil`.

**All four backends answer for real.** The interpreter and the JVM keep a per-handle position that the byte primitives advance, and reopen the file at the offset for the set. Preview 1 WASM queries and moves the descriptor's own cursor through `fd_seek`; the component backend has no cursor — WASI 0.3 reads are offset-based — so it goes through a per-descriptor byte offset the adapter tracks.

```lisp
(with-input-from-string (s "abc")
  (file-position s)) ; => NIL
```

```console
(with-open-file (in "data.bin" :element-type '(unsigned-byte 8))
  (print (file-position in 5))
  (print (read-byte in))
  (print (file-position in)))
```
