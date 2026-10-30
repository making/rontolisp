;; The flexi-streams package: a lite shim satisfying the built-in ASDF system
;; "flexi-streams". The IN-MEMORY streams are real -- an octet vector is not a
;; stream on any backend, and smart-buffer hands one to the multipart parser
;; for every request body that stayed under the memory limit -- and so is the
;; flexi-stream WRAPPER at the bottom of the file, a Gray stream that lends
;; characters to the octet stream it wraps. Written in canonical shape; the
;; package is seeded in PackageRegistry.
;;
;; Real flexi-streams is not loaded instead because nearly all of it is
;; encodings this build has no use for: 48,418 of its 52,557 lines are the GBK
;; table (enc-cn-tbl.lisp), most of the rest the ISO-8859 / code-page tables and
;; the macrology generating one class and codec per (encoding, eol style)
;; pair. A character IS a Unicode code point here, so the shim implements the
;; three external formats with no table -- UTF-8, ISO-8859-1 and US-ASCII --
;; and SIGNALS on any other name rather than mis-coding silently, the line the
;; babel shim draws too.

;; External formats, as upstream: an object carrying a canonical name and an
;; end-of-line style, made by make-external-format and accepted wherever an
;; :external-format is (string functions, the wrapper and its setf). A keyword
;; or a list (:utf-8 :eol-style :crlf) designates one, as upstream.
;;
;; One divergence: string-to-octets, octets-to-string and make-flexi-stream
;; default to :utf-8 where upstream defaults to :latin1. UTF-8 is the external
;; form of a string on every backend here, and the shim answered UTF-8 for
;; every format before it had others; a caller relying on upstream's default
;; would see a difference only above U+007F.

(defvar flexi-streams:*default-eol-style* :lf)

;; Read by the encoders and decoders below: when it is a character, a code
;; point the external format cannot carry codes as that character instead of
;; signalling.
(defvar flexi-streams:*substitution-char* nil)

(deftype flexi-streams:octet () '(unsigned-byte 8))

(defclass flexi-streams:external-format ()
  ((flexi-streams::name :initarg :name
                        :reader flexi-streams:external-format-name)
   (flexi-streams::eol-style :initarg :eol-style
                             :reader flexi-streams:external-format-eol-style)
   (flexi-streams::little-endian :initarg :little-endian
    :initform t
    :reader flexi-streams:external-format-little-endian)
   (flexi-streams::id :initarg :id
                      :initform nil
                      :reader flexi-streams:external-format-id)))

;; Upstream's +name-map+ for the three formats, compared by string-equal as
;; upstream compares (so "utf-8" and drakma's :UTF-8 from a charset both name
;; it). The common spellings are tried by eq first: every string function
;; normalizes its designator, and most callers pass a canonical keyword.
(defun flexi-streams::%external-format-name (name)
  (if (member name '(:utf-8 :iso-8859-1 :us-ascii))
      name
      (let ((s (string name)))
        (cond ((or (string-equal s "UTF-8") (string-equal s "UTF8")) :utf-8)
         ((or (string-equal s "ISO-8859-1") (string-equal s "LATIN-1")
              (string-equal s "LATIN1"))
          :iso-8859-1)
         ((or (string-equal s "US-ASCII") (string-equal s "ASCII")) :us-ascii)
         (t (error "flexi-streams: ~S is not an external format this build implements (it implements :UTF-8, :ISO-8859-1 and :US-ASCII)"
                   name))))))

(defun flexi-streams::%eol-style (eol-style)
  (if (member eol-style '(:lf :cr :crlf))
      eol-style
      (error "flexi-streams: ~S is not an end-of-line style (:LF, :CR or :CRLF)"
             eol-style)))

;; :little-endian and :id are recorded and select nothing: no implemented
;; format has more than one octet order or a code page.
(defun flexi-streams:make-external-format
    (name &key eol-style (little-endian t) id)
  (make-instance 'flexi-streams:external-format
                 :name (flexi-streams::%external-format-name name)
                 :eol-style (flexi-streams::%eol-style
                             (or eol-style flexi-streams:*default-eol-style*))
                 :little-endian little-endian
                 :id id))

;; upstream's maybe-convert-external-format
(defun flexi-streams::%as-external-format (designator)
  (cond ((typep designator 'flexi-streams:external-format) designator)
   ((consp designator) (apply #'flexi-streams:make-external-format designator))
   (t (flexi-streams:make-external-format designator))))

(defun flexi-streams:external-format-equal (ef1 ef2)
  (and (eq (flexi-streams:external-format-name ef1)
           (flexi-streams:external-format-name ef2))
       (eq (flexi-streams:external-format-eol-style ef1)
           (flexi-streams:external-format-eol-style ef2))))

;; The CODEC: one encoder and one decoder, shared by the string functions and
;; the wrapper. Each takes the canonical name and eol style rather than the
;; object, so a string function handed a keyword makes no object per call.

(defun flexi-streams::%substitute (what code name)
  (let ((sub flexi-streams:*substitution-char*))
    (if sub
        (char-code sub)
        (error "flexi-streams: cannot ~A ~S in the external format ~S" what code
               name))))

;; EMIT is called with each octet in order.
(defun flexi-streams::%encode-code (code name emit)
  (cond ((eq name :utf-8)
         (cond ((< code #x80) (funcall emit code))
               ((< code #x800)
                (funcall emit (logior #xC0 (ash code -6)))
                (funcall emit (logior #x80 (logand code #x3F))))
               ((< code #x10000)
                (funcall emit (logior #xE0 (ash code -12)))
                (funcall emit (logior #x80 (logand (ash code -6) #x3F)))
                (funcall emit (logior #x80 (logand code #x3F))))
               (t
                (funcall emit (logior #xF0 (ash code -18)))
                (funcall emit (logior #x80 (logand (ash code -12) #x3F)))
                (funcall emit (logior #x80 (logand (ash code -6) #x3F)))
                (funcall emit (logior #x80 (logand code #x3F))))))
        ((< code (if (eq name :iso-8859-1) #x100 #x80)) (funcall emit code))
        (t (funcall emit
            (flexi-streams::%substitute "encode the code point" code name)))))

(defun flexi-streams::%encode-char (char name eol emit)
  (if (and (char= char #\Newline) (not (eq eol :lf)))
      (progn
        (funcall emit 13)
        (if (eq eol :crlf) (funcall emit 10)))
      (flexi-streams::%encode-code (char-code char) name emit)))

;; NEXT answers the next octet, or :eof. A truncated UTF-8 sequence decodes as
;; if the missing continuation octets were zero: the shim never signals on
;; malformed UTF-8.
(defun flexi-streams::%continuation (next)
  (let ((byte (funcall next))) (if (eq byte :eof) 0 (logand byte #x3F))))

(defun flexi-streams::%decode-code (lead name next)
  (cond ((eq name :utf-8)
         (cond ((< lead #x80) lead)
               ((< lead #xE0)
                (logior (ash (logand lead #x1F) 6)
                        (flexi-streams::%continuation next)))
               ((< lead #xF0)
                (let ((second (flexi-streams::%continuation next)))
                  (logior (ash (logand lead #x0F) 12) (ash second 6)
                          (flexi-streams::%continuation next))))
               (t
                (let* ((second (flexi-streams::%continuation next))
                       (third (flexi-streams::%continuation next)))
                  (logior (ash (logand lead #x07) 18) (ash second 12)
                          (ash third 6) (flexi-streams::%continuation next))))))
        ((or (eq name :iso-8859-1) (< lead #x80)) lead)
        (t (flexi-streams::%substitute "decode the octet" lead name))))

;; Answers the character and, second, an octet read ahead and not consumed (or
;; nil): :crlf has to look past a CR to know whether it ends a line, and a CR
;; not followed by LF stays a CR, as upstream. All three formats code CR and LF
;; as the single octets 13 and 10, so the look-ahead reads one raw octet.
(defun flexi-streams::%decode-char (lead name eol next)
  (let ((code (flexi-streams::%decode-code lead name next)))
    (if (and (= code 13) (not (eq eol :lf)))
        (if (eq eol :cr)
            (values #\Newline nil)
            (let ((ahead (funcall next)))
              (cond ((eql ahead 10) (values #\Newline nil))
                    ((eq ahead :eof) (values (code-char 13) nil))
                    (t (values (code-char 13) ahead)))))
        (values (code-char code) nil))))

;; The designator's canonical name and eol style, without making an object.
(defun flexi-streams::%designator-name (ef)
  (cond ((typep ef 'flexi-streams:external-format)
         (flexi-streams:external-format-name ef))
        ((consp ef) (flexi-streams::%external-format-name (car ef)))
        (t (flexi-streams::%external-format-name ef))))

(defun flexi-streams::%designator-eol (ef)
  (cond ((typep ef 'flexi-streams:external-format)
         (flexi-streams:external-format-eol-style ef))
        ((consp ef)
         (flexi-streams::%eol-style
          (or (getf (cdr ef) :eol-style) flexi-streams:*default-eol-style*)))
        (t flexi-streams:*default-eol-style*)))

;; The result is a PACKED (non-adjustable) (unsigned-byte 8) array, like the
;; real flexi-streams' simple-array: an adjustable array does not carry its
;; declared element type here, and md5's etypecase dispatches the result on
;; (typep x '(array (unsigned-byte 8) (*))), which tests the element type.
(defun flexi-streams:string-to-octets
    (string &key (external-format :utf-8) (start 0) end)
  (let ((name (flexi-streams::%designator-name external-format))
        (eol (flexi-streams::%designator-eol external-format))
        (limit (or end (length string)))
        (bytes nil))
    (do ((i start (+ i 1)))
        ((>= i limit))
      (flexi-streams::%encode-char (char string i) name eol
                                   (lambda (b) (push b bytes))))
    (setq bytes (nreverse bytes))
    (let ((out (make-array (length bytes) :element-type '(unsigned-byte 8))))
      (let ((i 0))
        (dolist (b bytes)
          (setf (aref out i) b)
          (setq i (+ i 1))))
      out)))

(defun flexi-streams:octets-to-string
    (octets &key (external-format :utf-8) (start 0) end)
  (let* ((name (flexi-streams::%designator-name external-format))
         (eol (flexi-streams::%designator-eol external-format))
         (octets (if (listp octets) (coerce octets 'vector) octets))
         (limit (or end (length octets)))
         (i start)
         (next
          (lambda ()
            (if (< i limit)
                (let ((b (aref octets i)))
                  (setq i (+ i 1))
                  b)
                :eof))))
    (with-output-to-string (s)
      (do ()
          ((>= i limit))
        (multiple-value-bind (char ahead)
            (flexi-streams::%decode-char (funcall next) name eol next)
          (if ahead (setq i (- i 1)))
          (write-char char s))))))

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
;; one. A LIST is read as the vector of its elements (upstream's list-stream):
;; drakma's with-sequence-from-string hands one to with-input-from-sequence.
(defun flexi-streams:make-in-memory-input-stream
    (vector &key (start 0) end transformer)
  (declare (ignore transformer))
  (let ((vector (if (listp vector) (coerce vector 'vector) vector)))
    (make-instance 'flexi-streams::vector-input-stream
                   :vec vector
                   :index start
                   :end (or end (length vector)))))

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

;; The two macros, expanded as upstream expands them: the stream is closed on
;; any exit, and with-output-to-sequence answers what BODY wrote.
(defmacro flexi-streams:with-input-from-sequence
    ((var sequence &key start end transformer) &body body)
  (let ((seq (gensym "SEQUENCE")))
    `(let* ((,seq ,sequence) (,var nil))
       (unwind-protect (progn
                         (setq ,var
                               (flexi-streams:make-in-memory-input-stream ,seq
                                :start (or ,start 0)
                                :end ,end
                                :transformer ,transformer))
                         ,@body)
         (when ,var (close ,var))))))

(defmacro flexi-streams:with-output-to-sequence ((var &key as-list
                                                      (element-type
                                                       ''flexi-streams:octet)
                                                      transformer) &body body)
  `(let ((,var nil))
     (unwind-protect (progn
                       (setq ,var
                             (flexi-streams:make-in-memory-output-stream
                              :element-type ,element-type
                              :transformer ,transformer))
                       ,@body
                       (flexi-streams:get-output-stream-sequence ,var
                        :as-list ,as-list))
       (when ,var (close ,var)))))

;; The WRAPPER, for real. Upstream's flexi-stream wraps a BINARY stream and
;; lends it characters through an external format -- that is what the name
;; means everywhere it appears, and cl+ssl's
;; (defmethod ssl-stream-handle ((stream flexi-streams:flexi-stream)) ...)
;; needs the class to exist at all. The wrapper therefore reads and writes
;; OCTETS on the stream it wraps, so that stream must be binary-capable (an
;; in-memory octet stream, a socket, a binary file stream) -- the same
;; requirement upstream states.
;;
;; The external format is an external-format object (a designator is converted
;; on the way in, by the constructor and the setf alike), and it selects the
;; codec: drakma reads and writes headers through one stream as Latin-1 and
;; switches it to the body's charset by setf-ing it. The element type is
;; recorded and readable back but selects nothing: the stream is bivalent, so
;; read-byte and read-char both work whatever it says, and a non-string
;; sequence handed to read-sequence is filled with octets.

(defclass flexi-streams:flexi-stream (rontolisp:fundamental-character-input-stream
                                      rontolisp:fundamental-character-output-stream
                                      rontolisp:fundamental-binary-input-stream
                                      rontolisp:fundamental-binary-output-stream)
  ((flexi-streams::stream :initarg :stream
                          :initform nil
                          :reader flexi-streams:flexi-stream-stream)
   (flexi-streams::external-format :initarg :external-format
    :reader flexi-streams:flexi-stream-external-format)
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
                         :accessor flexi-streams:flexi-stream-bound)
   ;; The octet a :crlf read looked ahead at past a CR and did not consume;
   ;; the position does not count it until it is read again.
   (flexi-streams::ahead :initform nil
                         :accessor flexi-streams::flexi-stream-ahead)))

(defun (setf flexi-streams:flexi-stream-external-format)
    (external-format stream)
  (setf (slot-value stream 'flexi-streams::external-format)
        (flexi-streams::%as-external-format external-format)))

;; :column is accepted and ignored (upstream seeds the output column counter
;; with it, and nothing here tracks one).
(defun flexi-streams:make-flexi-stream (stream &key (external-format :utf-8)
                                               (element-type 'character)
                                               (position 0) bound column)
  (declare (ignore column))
  (make-instance 'flexi-streams:flexi-stream
   :stream stream
   :external-format (flexi-streams::%as-external-format external-format)
   :element-type element-type
   :position position
   :bound bound))

(defun flexi-streams::%flexi-read-octet (stream)
  (let ((ahead (flexi-streams::flexi-stream-ahead stream))
        (bound (flexi-streams:flexi-stream-bound stream)))
    (cond
     (ahead
      (setf (flexi-streams::flexi-stream-ahead stream) nil)
      (setf (flexi-streams:flexi-stream-position stream)
            (+ (flexi-streams:flexi-stream-position stream) 1))
      ahead)
     ((and bound (>= (flexi-streams:flexi-stream-position stream) bound)) :eof)
     (t (let ((byte
               (read-byte (flexi-streams:flexi-stream-stream stream) nil nil)))
          (if byte
              (progn
                (setf (flexi-streams:flexi-stream-position stream)
                      (+ (flexi-streams:flexi-stream-position stream) 1))
                byte)
              :eof))))))

(defmethod rontolisp:stream-read-byte ((stream flexi-streams:flexi-stream))
  (flexi-streams::%flexi-read-octet stream))

(defmethod rontolisp:stream-read-char ((stream flexi-streams:flexi-stream))
  (let ((lead (flexi-streams::%flexi-read-octet stream)))
    (if (eq lead :eof)
        :eof (let ((ef (flexi-streams:flexi-stream-external-format stream)))
               (multiple-value-bind (char ahead) (flexi-streams::%decode-char
                                                  lead
                                                  (flexi-streams:external-format-name
                                                   ef)
                                                  (flexi-streams:external-format-eol-style
                                                   ef)
                                                  (lambda ()
                                                    (flexi-streams::%flexi-read-octet
                                                     stream)))
                 (if ahead
                     (progn
                       (setf (flexi-streams::flexi-stream-ahead stream) ahead)
                       (setf (flexi-streams:flexi-stream-position stream)
                        (- (flexi-streams:flexi-stream-position stream) 1))))
                 char)))))

(defmethod rontolisp:stream-write-byte
    ((stream flexi-streams:flexi-stream) byte)
  (write-byte byte (flexi-streams:flexi-stream-stream stream))
  byte)

(defmethod rontolisp:stream-write-char
    ((stream flexi-streams:flexi-stream) char)
  (let ((inner (flexi-streams:flexi-stream-stream stream))
        (ef (flexi-streams:flexi-stream-external-format stream)))
    (flexi-streams::%encode-char char (flexi-streams:external-format-name ef)
                                 (flexi-streams:external-format-eol-style ef)
                                 (lambda (b) (write-byte b inner))))
  char)

(defmethod rontolisp:stream-listen ((stream flexi-streams:flexi-stream))
  (let ((bound (flexi-streams:flexi-stream-bound stream)))
    (cond ((flexi-streams::flexi-stream-ahead stream) t)
     ((and bound (>= (flexi-streams:flexi-stream-position stream) bound)) nil)
     (t (listen (flexi-streams:flexi-stream-stream stream))))))

;; The position is the wrapper's own octet counter, as upstream: the writer
;; seeks the underlying stream and re-bases the counter on the answer.
(defmethod rontolisp:stream-file-position ((stream flexi-streams:flexi-stream))
  (flexi-streams:flexi-stream-position stream))

(defmethod (setf rontolisp:stream-file-position)
    (position (stream flexi-streams:flexi-stream))
  (file-position (flexi-streams:flexi-stream-stream stream) position)
  (setf (flexi-streams::flexi-stream-ahead stream) nil)
  (setf (flexi-streams:flexi-stream-position stream) position))

(defmethod rontolisp:stream-force-output ((stream flexi-streams:flexi-stream))
  (force-output (flexi-streams:flexi-stream-stream stream))
  nil)

(defmethod rontolisp:stream-finish-output ((stream flexi-streams:flexi-stream))
  (finish-output (flexi-streams:flexi-stream-stream stream))
  nil)
