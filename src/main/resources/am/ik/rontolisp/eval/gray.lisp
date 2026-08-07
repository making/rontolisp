;; rontolisp's own Gray-stream extension: the base classes and generic
;; functions a user-defined stream implements, mirroring how real
;; implementations expose their native Gray support (sb-gray, ccl's
;; ansi-streams, ...). The stream-taking built-ins (write-string/write-char,
;; write-byte, read-byte/read-char/read-line, listen,
;; read-sequence/write-sequence, file-position) dispatch to the
;; rontolisp:stream-* generics when handed a CLOS instance instead of a
;; stream handle. Third-party portability layers adapt to THIS protocol (see
;; trivial-gray-streams.lisp); no third-party name is known to the core.
;;
;; Read-side EOF convention: stream-read-byte / stream-read-char /
;; stream-read-line return the keyword :eof at end of stream; the built-in
;; dispatch translates that into the eof-error-p / eof-value contract
;; (signalling end-of-file like the handle-based built-ins).
;; stream-read-line answers a partial last line as that line; :eof means "no
;; characters left at all". Primary values only -- no (values line
;; missing-newline-p) pair crosses a function boundary on the compile
;; backends.

(defclass rontolisp:fundamental-stream () ())

(defclass rontolisp:fundamental-input-stream (rontolisp:fundamental-stream) ())

(defclass rontolisp:fundamental-output-stream (rontolisp:fundamental-stream) ())

(defclass rontolisp:fundamental-character-output-stream
    (rontolisp:fundamental-output-stream)
  ())

(defclass rontolisp:fundamental-character-input-stream
    (rontolisp:fundamental-input-stream)
  ())

(defclass rontolisp:fundamental-binary-input-stream
    (rontolisp:fundamental-input-stream)
  ())

(defclass rontolisp:fundamental-binary-output-stream
    (rontolisp:fundamental-output-stream)
  ())

;; Output generics.

(defgeneric rontolisp:stream-write-char (stream character))

(defgeneric rontolisp:stream-write-string (stream string &optional start end))

(defgeneric rontolisp:stream-write-byte (stream byte))

;; Input generics.

(defgeneric rontolisp:stream-read-byte (stream))

(defgeneric rontolisp:stream-read-char (stream))

(defgeneric rontolisp:stream-unread-char (stream character))

(defgeneric rontolisp:stream-read-line (stream))

(defgeneric rontolisp:stream-listen (stream))

;; Sequence generics: start/end are always integers by the time a method
;; runs -- the dispatch helpers below normalize a missing end to
;; (length sequence), mirroring the built-in read-sequence/write-sequence.

(defgeneric rontolisp:stream-read-sequence (stream sequence start end))

(defgeneric rontolisp:stream-write-sequence (stream sequence start end))

;; file-position protocol: (file-position s) reads through the generic,
;; (file-position s pos) writes through the (setf ...) generic.

(defgeneric rontolisp:stream-file-position (stream))

(defgeneric (setf rontolisp:stream-file-position) (position stream))

;; Default methods: element-at-a-time fallbacks mirroring full Gray's
;; defaults, so a class defining only the element generics still answers
;; read-line and the sequence built-ins. The loops are plain defuns so the
;; trivial-gray-streams shim can reuse them for its own defaults.

(defun rontolisp::%gray-default-read-line (stream)
  (let ((acc "") (result nil) (done nil))
    (do ()
        (done result)
      (let ((c (rontolisp:stream-read-char stream)))
        (cond ((eq c :eof)
               (setq result (if (string= acc "") :eof acc))
               (setq done t))
              ((char= c #\Newline)
               (setq result acc)
               (setq done t))
              (t (setq acc (concatenate 'string acc (string c)))))))))

(defun rontolisp::%gray-default-read-sequence (stream sequence start end)
  (let ((i start) (done nil) (chars (stringp sequence)))
    (do ()
        ((or done (>= i end)) i)
      (let ((elt
             (if chars
                 (rontolisp:stream-read-char stream)
                 (rontolisp:stream-read-byte stream))))
        (if (eq elt :eof)
            (setq done t)
            (progn
              (setf (aref sequence i) elt)
              (setq i (+ i 1))))))))

(defun rontolisp::%gray-default-write-sequence (stream sequence start end)
  (let ((i start) (chars (stringp sequence)))
    (do ()
        ((>= i end) sequence)
      (if chars
          (rontolisp:stream-write-char stream (aref sequence i))
          (rontolisp:stream-write-byte stream (aref sequence i)))
      (setq i (+ i 1)))))

(defmethod rontolisp:stream-read-line
    ((stream rontolisp:fundamental-input-stream))
  (rontolisp::%gray-default-read-line stream))

(defmethod rontolisp:stream-listen ((stream rontolisp:fundamental-input-stream))
  nil)

(defmethod rontolisp:stream-read-sequence
    ((stream rontolisp:fundamental-input-stream) sequence start end)
  (rontolisp::%gray-default-read-sequence stream sequence start end))

(defmethod rontolisp:stream-write-sequence
    ((stream rontolisp:fundamental-output-stream) sequence start end)
  (rontolisp::%gray-default-write-sequence stream sequence start end))

;; file-position defaults: nil is CL's "not supported" answer, the same one
;; the handle-based built-in gives.

(defmethod rontolisp:stream-file-position
    ((stream rontolisp:fundamental-stream))
  nil)

(defmethod (setf rontolisp:stream-file-position)
    (position (stream rontolisp:fundamental-stream))
  nil)

;; The built-in dispatch helpers the compile path rewrites call sites onto
;; (GrayStreamsLibrary.process) and the interpreter's built-in wraps delegate
;; to: a CLOS instance stream goes to the Gray generic, anything else falls
;; back to the built-in. The interpreter needs no rewrite -- its built-in
;; wraps call these helpers directly.

(defun rontolisp::%gray-write-string-dispatch (s stream)
  (if (%obj-p stream)
      (rontolisp:stream-write-string stream s)
      (write-string s stream)))

(defun rontolisp::%gray-write-char-dispatch (c stream)
  ;; write-char lowers to write-string everywhere, so the dispatch does too.
  (rontolisp::%gray-write-string-dispatch (string c) stream))

(defun rontolisp::%gray-write-byte-dispatch (byte stream)
  (if (%obj-p stream)
      (progn
        (rontolisp:stream-write-byte stream byte)
        byte)
      (write-byte byte stream)))

(defun rontolisp::%gray-read-byte-dispatch (stream eof-error-p eof-value)
  (if (%obj-p stream)
      (let ((b (rontolisp:stream-read-byte stream)))
        (if (eq b :eof) (if eof-error-p (error 'end-of-file) eof-value) b))
      (read-byte stream eof-error-p eof-value)))

(defun rontolisp::%gray-read-char-dispatch (stream eof-error-p eof-value)
  (if (%obj-p stream)
      (let ((c (rontolisp:stream-read-char stream)))
        (if (eq c :eof) (if eof-error-p (error 'end-of-file) eof-value) c))
      (read-char stream eof-error-p eof-value)))

(defun rontolisp::%gray-read-line-dispatch (stream eof-error-p eof-value)
  (if (%obj-p stream)
      (let ((l (rontolisp:stream-read-line stream)))
        (if (eq l :eof) (if eof-error-p (error 'end-of-file) eof-value) l))
      (read-line stream eof-error-p eof-value)))

(defun rontolisp::%gray-listen-dispatch (stream)
  (if (%obj-p stream)
      (if (rontolisp:stream-listen stream) t nil)
      (listen stream)))

(defun rontolisp::%gray-read-sequence-dispatch (sequence stream start end)
  (if (%obj-p stream)
      (rontolisp:stream-read-sequence stream sequence start
                                      (if end end (length sequence)))
      (read-sequence sequence stream
                     :start start
                     :end (if end end (length sequence)))))

(defun rontolisp::%gray-write-sequence-dispatch (sequence stream start end)
  (if (%obj-p stream)
      (progn
        (rontolisp:stream-write-sequence stream sequence start
                                         (if end end (length sequence)))
        sequence)
      (write-sequence sequence stream
                      :start start
                      :end (if end end (length sequence)))))

(defun rontolisp::%gray-file-position-dispatch (stream)
  (if (%obj-p stream)
      (rontolisp:stream-file-position stream)
      (file-position stream)))

(defun rontolisp::%gray-file-position-set-dispatch (stream position)
  (if (%obj-p stream)
      (setf (rontolisp:stream-file-position stream) position)
      (file-position stream position)))
