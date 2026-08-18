# unread-char

`(unread-char character &optional stream)`

Puts `character` -- which must be the one just read -- back so the next read returns it again, and answers `nil`. On a [Gray stream](../../guides/gray-streams.md) instance it dispatches to `rontolisp:stream-unread-char`, whose default method parks the character in the protocol's one-slot pushback; a class that can rewind its own source defines that generic instead. On a stream HANDLE -- a file, a string input stream, a socket -- the character goes into a handle-side pushback of its own, which `read-char`, `peek-char`, `read-char-no-hang` and `read-line` drain.

One character for one stream is all either cell holds, which is what CL promises: a second `unread-char` with the cell still full signals. `read-byte`, `read-sequence` and `read` do not consult the handle-side cell.

```lisp
(let* ((s (make-string-input-stream "abc"))
       (c (read-char s)))
  (unread-char c s)
  (list c (peek-char nil s) (read-char s) (read-line s))) ; => (#\a #\a #\a "bc")
```
