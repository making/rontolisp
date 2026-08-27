;; The flexi-streams package: a lite shim satisfying the built-in ASDF system
;; "flexi-streams". The IN-MEMORY streams are real -- an octet vector is not a
;; stream on any backend, and smart-buffer hands one to the multipart parser
;; for every request body that stayed under the memory limit -- and so is the
;; flexi-stream WRAPPER at the bottom of the file, a Gray stream that lends
;; characters to the octet stream it wraps. Written in canonical shape; the
;; package is seeded in PackageRegistry.

;; UTF-8 is the only external format the shim implements: callers reaching it
;; pass :utf-8 or :default (md5's md5sum-string does), and rontolisp strings
;; hold full code points, so the encoder covers the whole Unicode range.
;; The result is a PACKED (non-adjustable) (unsigned-byte 8) array, like the
;; real flexi-streams' simple-array: an adjustable array does not carry its
;; declared element type here, and md5's etypecase dispatches the result on
;; (typep x '(array (unsigned-byte 8) (*))), which tests the element type.
(defun flexi-streams:string-to-octets
    (string &key (external-format :utf-8) (start 0) end)
  (declare (ignore external-format))
  (let ((limit (or end (length string))) (bytes nil))
    (do ((i start (+ i 1)))
        ((>= i limit))
      (let ((c (char-code (char string i))))
        (cond ((< c #x80) (push c bytes))
              ((< c #x800)
               (push (logior #xC0 (ash c -6)) bytes)
               (push (logior #x80 (logand c #x3F)) bytes))
              ((< c #x10000)
               (push (logior #xE0 (ash c -12)) bytes)
               (push (logior #x80 (logand (ash c -6) #x3F)) bytes)
               (push (logior #x80 (logand c #x3F)) bytes))
              (t
               (push (logior #xF0 (ash c -18)) bytes)
               (push (logior #x80 (logand (ash c -12) #x3F)) bytes)
               (push (logior #x80 (logand (ash c -6) #x3F)) bytes)
               (push (logior #x80 (logand c #x3F)) bytes)))))
    (setq bytes (nreverse bytes))
    (let ((out (make-array (length bytes) :element-type '(unsigned-byte 8))))
      (let ((i 0))
        (dolist (b bytes)
          (setf (aref out i) b)
          (setq i (+ i 1))))
      out)))

(defun flexi-streams:octets-to-string
    (octets &key (external-format :utf-8) (start 0) end)
  (declare (ignore external-format))
  (let ((limit (or end (length octets))))
    (with-output-to-string (s)
      (do ((i start))
          ((>= i limit))
        (let ((b (aref octets i)))
          (cond ((< b #x80)
                 (write-char (code-char b) s)
                 (setq i (+ i 1)))
                ((< b #xE0)
                 (write-char (code-char
                              (logior (ash (logand b #x1F) 6)
                                      (logand (aref octets (+ i 1)) #x3F))) s)
                 (setq i (+ i 2)))
                ((< b #xF0)
                 (write-char (code-char
                              (logior (ash (logand b #x0F) 12)
                               (ash (logand (aref octets (+ i 1)) #x3F) 6)
                               (logand (aref octets (+ i 2)) #x3F))) s)
                 (setq i (+ i 3)))
                (t
                 (write-char (code-char
                              (logior (ash (logand b #x07) 18)
                               (ash (logand (aref octets (+ i 1)) #x3F) 12)
                               (ash (logand (aref octets (+ i 2)) #x3F) 6)
                               (logand (aref octets (+ i 3)) #x3F))) s)
                 (setq i (+ i 4)))))))))

;; The in-memory octet streams, over rontolisp's own Gray protocol
;; (gray.lisp) rather than over trivial-gray-streams: the two shims are
;; independent ASDF systems, and a program naming only flexi-streams must not
;; drag the portability adapter in. vector-stream is the class http-body's
;; slurp-stream type-tests against to take its no-copy fast path; the slot
;; accessors are internal, as upstream.

(defclass flexi-streams:vector-stream
    (rontolisp:fundamental-binary-input-stream)
  ((flexi-streams::vec :initarg :vec
                       :initform nil
                       :accessor flexi-streams::vector-stream-vector)
   (flexi-streams::index :initarg :index
                         :initform 0
                         :accessor flexi-streams::vector-stream-index)
   (flexi-streams::end :initarg :end
                       :initform 0
                       :accessor flexi-streams::vector-stream-end)))

(defclass flexi-streams::vector-input-stream (flexi-streams:vector-stream) ())

;; :transformer is accepted and ignored: it exists upstream to re-map each
;; element on the way out, and no caller in the lack/http-body chain passes
;; one.
(defun flexi-streams:make-in-memory-input-stream
    (vector &key (start 0) end transformer)
  (declare (ignore transformer))
  (make-instance 'flexi-streams::vector-input-stream
                 :vec vector
                 :index start
                 :end (or end (length vector))))

(defmethod rontolisp:stream-read-byte ((stream flexi-streams:vector-stream))
  (let ((i (flexi-streams::vector-stream-index stream)))
    (if (>= i (flexi-streams::vector-stream-end stream))
        :eof (progn
               (setf (flexi-streams::vector-stream-index stream) (+ i 1))
               (aref (flexi-streams::vector-stream-vector stream) i)))))

(defmethod rontolisp:stream-listen ((stream flexi-streams:vector-stream))
  (< (flexi-streams::vector-stream-index stream)
     (flexi-streams::vector-stream-end stream)))

;; file-position is real here (an index into a vector), which is what lets
;; circular-streams rewind a body it has already read.

(defmethod rontolisp:stream-file-position ((stream flexi-streams:vector-stream))
  (flexi-streams::vector-stream-index stream))

(defmethod (setf rontolisp:stream-file-position)
    (position (stream flexi-streams:vector-stream))
  (setf (flexi-streams::vector-stream-index stream) position))

;; The WRITE half of the in-memory pair. Deliberately NOT a vector-stream:
;; that class is what http-body type-tests to take its no-copy input path,
;; and a sink has no readable vector to hand it. The bytes accumulate in an
;; adjustable fill-pointer vector and come back PACKED, the shape
;; string-to-octets already answers with.

(defclass flexi-streams::vector-output-stream
    (rontolisp:fundamental-binary-output-stream)
  ((flexi-streams::vec :initarg :vec
                       :initform nil
                       :accessor flexi-streams::vector-stream-vector)))

;; :element-type and :transformer are accepted and ignored: every in-memory
;; sink here is an octet sink, and no caller re-maps its elements on the way
;; in.
(defun flexi-streams:make-in-memory-output-stream
    (&key element-type transformer (initial-size 32))
  (declare (ignore element-type transformer))
  (make-instance 'flexi-streams::vector-output-stream
                 :vec (make-array initial-size
                                  :element-type '(unsigned-byte 8)
                                  :adjustable t
                                  :fill-pointer 0)))

(defmethod rontolisp:stream-write-byte
    ((stream flexi-streams::vector-output-stream) byte)
  (vector-push-extend byte (flexi-streams::vector-stream-vector stream))
  byte)

;; Upstream RESETS the stream, so a second call answers only what was written
;; after the first.
(defun flexi-streams:get-output-stream-sequence (stream &key as-list)
  (let* ((buffer (flexi-streams::vector-stream-vector stream))
         (result
          (make-array (fill-pointer buffer) :element-type '(unsigned-byte 8))))
    (dotimes (i (fill-pointer buffer)) (setf (aref result i) (aref buffer i)))
    (setf (fill-pointer buffer) 0)
    (if as-list (coerce result 'list) result)))

;; The WRAPPER, for real. Upstream's flexi-stream wraps a BINARY stream and
;; lends it characters through an external format -- that is what the name
;; means everywhere it appears, and cl+ssl's
;; (defmethod ssl-stream-handle ((stream flexi-streams:flexi-stream)) ...)
;; needs the class to exist at all. The wrapper therefore reads and writes
;; OCTETS on the stream it wraps, so that stream must be binary-capable (an
;; in-memory octet stream, a socket, a binary file stream) -- the same
;; requirement upstream states. Before this the shim answered the underlying
;; stream itself, which made (make-flexi-stream <octet sink>) a lie: writing a
;; character to the answer found no applicable method.
;;
;; UTF-8 is the only external format here, as for string-to-octets above:
;; :external-format is recorded (and readable back) but selects no codec.

(defclass flexi-streams:flexi-stream (rontolisp:fundamental-character-input-stream
                                      rontolisp:fundamental-character-output-stream
                                      rontolisp:fundamental-binary-input-stream
                                      rontolisp:fundamental-binary-output-stream)
  ((flexi-streams::stream :initarg :stream
                          :initform nil
                          :reader flexi-streams:flexi-stream-stream)
   (flexi-streams::external-format :initarg :external-format
    :initform :utf-8
    :accessor flexi-streams:flexi-stream-external-format)
   (flexi-streams::element-type :initarg :element-type
    :initform 'character
    :accessor flexi-streams:flexi-stream-element-type)
   ;; The octet counter, and the absolute octet position reading stops at.
   ;; jzon's (jzon:span stream :start s :end e) is the caller that passes both.
   (flexi-streams::position :initarg :position
                            :initform 0
                            :accessor flexi-streams:flexi-stream-position)
   (flexi-streams::bound :initarg :bound
                         :initform nil
                         :accessor flexi-streams:flexi-stream-bound)))

;; :column is accepted and ignored (upstream seeds the output column counter
;; with it, and nothing here tracks one).
(defun flexi-streams:make-flexi-stream (stream &key (external-format :utf-8)
                                               (element-type 'character)
                                               (position 0) bound column)
  (declare (ignore column))
  (make-instance 'flexi-streams:flexi-stream
                 :stream stream
                 :external-format external-format
                 :element-type element-type
                 :position position
                 :bound bound))

(defun flexi-streams::%flexi-read-octet (stream)
  (let ((bound (flexi-streams:flexi-stream-bound stream)))
    (if (and bound (>= (flexi-streams:flexi-stream-position stream) bound))
        :eof (let ((byte
                    (read-byte (flexi-streams:flexi-stream-stream stream) nil
                               nil)))
               (if byte
                   (progn
                     (setf (flexi-streams:flexi-stream-position stream)
                           (+ (flexi-streams:flexi-stream-position stream) 1))
                     byte)
                   :eof)))))

;; A truncated sequence decodes as if the missing continuation bytes were
;; zero, the same tolerance octets-to-string above has: the shim never
;; signals on malformed input.
(defun flexi-streams::%flexi-continuation (stream)
  (let ((byte (flexi-streams::%flexi-read-octet stream)))
    (if (eq byte :eof) 0 (logand byte #x3F))))

(defun flexi-streams::%flexi-decode (stream lead)
  (cond ((< lead #x80) lead)
        ((< lead #xE0)
         (logior (ash (logand lead #x1F) 6)
                 (flexi-streams::%flexi-continuation stream)))
        ((< lead #xF0)
         (let ((second (flexi-streams::%flexi-continuation stream)))
           (logior (ash (logand lead #x0F) 12) (ash second 6)
                   (flexi-streams::%flexi-continuation stream))))
        (t (let* ((second (flexi-streams::%flexi-continuation stream))
                  (third (flexi-streams::%flexi-continuation stream)))
             (logior (ash (logand lead #x07) 18) (ash second 12) (ash third 6)
                     (flexi-streams::%flexi-continuation stream))))))

(defmethod rontolisp:stream-read-byte ((stream flexi-streams:flexi-stream))
  (flexi-streams::%flexi-read-octet stream))

(defmethod rontolisp:stream-read-char ((stream flexi-streams:flexi-stream))
  (let ((lead (flexi-streams::%flexi-read-octet stream)))
    (if (eq lead :eof)
        :eof (code-char (flexi-streams::%flexi-decode stream lead)))))

(defmethod rontolisp:stream-write-byte
    ((stream flexi-streams:flexi-stream) byte)
  (write-byte byte (flexi-streams:flexi-stream-stream stream))
  byte)

(defmethod rontolisp:stream-write-char
    ((stream flexi-streams:flexi-stream) char)
  (let ((inner (flexi-streams:flexi-stream-stream stream))
        (code (char-code char)))
    (cond ((< code #x80) (write-byte code inner))
          ((< code #x800)
           (write-byte (logior #xC0 (ash code -6)) inner)
           (write-byte (logior #x80 (logand code #x3F)) inner))
          ((< code #x10000)
           (write-byte (logior #xE0 (ash code -12)) inner)
           (write-byte (logior #x80 (logand (ash code -6) #x3F)) inner)
           (write-byte (logior #x80 (logand code #x3F)) inner))
          (t
           (write-byte (logior #xF0 (ash code -18)) inner)
           (write-byte (logior #x80 (logand (ash code -12) #x3F)) inner)
           (write-byte (logior #x80 (logand (ash code -6) #x3F)) inner)
           (write-byte (logior #x80 (logand code #x3F)) inner))))
  char)

(defmethod rontolisp:stream-listen ((stream flexi-streams:flexi-stream))
  (let ((bound (flexi-streams:flexi-stream-bound stream)))
    (if (and bound (>= (flexi-streams:flexi-stream-position stream) bound))
        nil
        (listen (flexi-streams:flexi-stream-stream stream)))))

;; The position is the wrapper's own octet counter, as upstream: the writer
;; seeks the underlying stream and re-bases the counter on the answer.
(defmethod rontolisp:stream-file-position ((stream flexi-streams:flexi-stream))
  (flexi-streams:flexi-stream-position stream))

(defmethod (setf rontolisp:stream-file-position)
    (position (stream flexi-streams:flexi-stream))
  (file-position (flexi-streams:flexi-stream-stream stream) position)
  (setf (flexi-streams:flexi-stream-position stream) position))

(defmethod rontolisp:stream-force-output ((stream flexi-streams:flexi-stream))
  (force-output (flexi-streams:flexi-stream-stream stream))
  nil)

(defmethod rontolisp:stream-finish-output ((stream flexi-streams:flexi-stream))
  (finish-output (flexi-streams:flexi-stream-stream stream))
  nil)
