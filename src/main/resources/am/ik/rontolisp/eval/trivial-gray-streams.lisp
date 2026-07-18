;; The trivial-gray-streams package: a thin adapter over rontolisp's own
;; Gray-stream extension (gray.lisp), satisfying the built-in ASDF system
;; "trivial-gray-streams" -- the same reasoning as the usocket shim: the real
;; library is a per-implementation portability layer that cannot know
;; rontolisp, so rontolisp carries the adaptation. A user class extends
;; trivial-gray-streams:fundamental-character-output-stream and defines
;; methods on trivial-gray-streams:stream-write-char/stream-write-string; the
;; delegating methods below route rontolisp's protocol (which the
;; write-char/write-string built-ins dispatch to) into those generics.
;; Written in canonical shape; the package is seeded in PackageRegistry.

(defclass trivial-gray-streams:fundamental-character-output-stream
  (rontolisp:fundamental-character-output-stream) ())

(defclass trivial-gray-streams:fundamental-character-input-stream
  (rontolisp:fundamental-character-input-stream) ())

(defgeneric trivial-gray-streams:stream-write-char (stream character))

(defgeneric trivial-gray-streams:stream-write-string (stream string &optional start end))

(defmethod rontolisp:stream-write-char
  ((stream trivial-gray-streams:fundamental-character-output-stream) character)
  (trivial-gray-streams:stream-write-char stream character))

(defmethod rontolisp:stream-write-string
  ((stream trivial-gray-streams:fundamental-character-output-stream) string &optional start end)
  (trivial-gray-streams:stream-write-string stream string start end))
