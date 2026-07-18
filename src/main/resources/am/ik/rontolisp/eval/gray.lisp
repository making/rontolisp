;; rontolisp's own Gray-stream extension: the base classes and generic
;; functions a user-defined character output stream implements, mirroring how
;; real implementations expose their native Gray support (sb-gray, ccl's
;; ansi-streams, ...). The write-char/write-string built-ins dispatch to
;; rontolisp:stream-write-string when handed a CLOS instance instead of a
;; stream handle. Third-party portability layers adapt to THIS protocol (see
;; trivial-gray-streams.lisp); no third-party name is known to the core.

(defclass rontolisp:fundamental-character-output-stream () ())

(defclass rontolisp:fundamental-character-input-stream () ())

(defgeneric rontolisp:stream-write-char (stream character))

(defgeneric rontolisp:stream-write-string (stream string &optional start end))
