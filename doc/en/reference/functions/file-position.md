# file-position

`(file-position stream [position])`

With one argument, the current position of a file stream. A **binary** one — opened with an integer `:element-type` — counts in its elements (bytes for `'(unsigned-byte 8)`). A **character** one counts bytes, as SBCL does: a character advances it by its UTF-8 length, a line read by `read-line` ends after its terminator (both bytes of a CRLF), a character `peek-char` looked at or `unread-char` pushed back is not consumed yet, and a stream opened with `:if-exists :append` starts at the end of the file. With two arguments, it repositions the stream to `position` and answers `t`; the next read or write starts there. `position` may also be `:start` or `:end`.

Anything whose position cannot be determined answers `nil`, which is what Common Lisp prescribes for exactly that: a string stream, a socket, one of the standard streams, and a handle that has already been closed. Portable callers guard the call with `ignore-errors` and take their non-seeking fallback path on `nil`.

**All four backends answer for real.** The interpreter and the JVM count what the byte primitives move for a binary stream, read the channel offset of a character or bidirectional stream, and reopen or reposition the file for the set. Preview 1 WASM queries and moves the descriptor's own cursor through `fd_seek`; the component backend has no cursor — WASI 0.3 reads are offset-based — so it goes through a per-descriptor byte offset the adapter tracks.

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
