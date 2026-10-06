# d73. The Gray default `stream-unread-char` cell is one slot for every instance

Difficulty: Medium

`gray.lisp`'s default `stream-unread-char` parks the character in ONE program-wide cell
(`rontolisp::*gray-unread-stream*` / `*gray-unread-char*`, `.kb/gray-streams.md`, "Protocol"), and
it OVERWRITES without checking. Two instances of a class that defines only `stream-read-char`,
each unread once, lose the first character silently. Measured 2026-10-06, all four backends:

```lisp
(defclass src (rontolisp:fundamental-character-input-stream)
  ((text :initarg :text) (pos :initform 0)))
(defmethod rontolisp:stream-read-char ((s src))
  (with-slots (text pos) s
    (if (< pos (length text)) (prog1 (char text pos) (incf pos)) :eof)))
(let ((a (make-instance 'src :text "abc")) (b (make-instance 'src :text "xyz")))
  (unread-char (read-char a) a)
  (unread-char (read-char b) b)
  (print (list (read-char a) (read-char b) (read-char a))))
```

Every backend prints `(#\b #\x #\c)`; per-stream pushback answers `(#\a #\x #\b)`. SBCL's
`sb-gray` has no default method at all (no applicable method for `stream-unread-char`), so the
default is rontolisp's own and its contract is ours to fix. A cell left by a dropped instance
also lingers until the next unread on any instance.

The handle-side pushback moved onto the stream value (`LispLayout.STREAM_PUSHBACK_CELL`); the Gray
cell needs the same home on the INSTANCE. Decide how without adding a slot users see through
`class-slots` / `describe` / the printers (a reserved cell past the declared slots, as the
stream layout has, or a slot the MOP listings filter), on all four backends together.
