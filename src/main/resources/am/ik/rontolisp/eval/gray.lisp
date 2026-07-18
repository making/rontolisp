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

;; The write-string/write-char dispatch helpers the compile path rewrites call
;; sites onto (GrayStreamsLibrary.process): a CLOS instance stream goes to the
;; Gray generic, anything else falls back to the built-in. The interpreter
;; needs no rewrite -- its write-string wrapper dispatches natively.

(defun rontolisp::%gray-write-string-dispatch (s stream)
  (if (consp stream)
      (rontolisp:stream-write-string stream s)
      (write-string s stream)))

(defun rontolisp::%gray-write-char-dispatch (c stream)
  ;; write-char lowers to write-string everywhere, so the dispatch does too.
  (rontolisp::%gray-write-string-dispatch (string c) stream))
