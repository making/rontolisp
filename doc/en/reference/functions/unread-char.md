# unread-char

`(unread-char character &optional stream)`

Puts `character` -- which must be the one just read -- back so the next read returns it again, and answers `nil`. Supported on a [Gray stream](../../guides/gray-streams.md) instance only: it dispatches to `rontolisp:stream-unread-char`, whose default method parks the character in the protocol's one-slot pushback, and a class that can rewind its own source defines that generic instead. A stream HANDLE -- a file, a string input stream, a socket -- has no pushback on any backend, so that arm signals rather than dropping the character silently.

```lisp
(defclass uc-source (rontolisp:fundamental-character-input-stream)
  ((text :initarg :text) (pos :initform 0)))
(defmethod rontolisp:stream-read-char ((s uc-source))
  (let ((text (slot-value s 'text)) (pos (slot-value s 'pos)))
    (if (>= pos (length text))
        :eof
        (progn (setf (slot-value s 'pos) (+ pos 1)) (char text pos)))))
(let* ((s (make-instance 'uc-source :text "ab"))
       (c (read-char s)))
  (unread-char c s)
  (list c (read-char s) (read-char s))) ; => (#\a #\a #\b)
```
