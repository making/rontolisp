;; The babel + babel-encodings packages: the UTF-8 slice of Luis Oliveira's
;; charset-conversion library, satisfying the built-in ASDF system "babel".
;;
;; Real babel generates 40+ concrete encodings (every ISO-8859 part, the
;; Windows code pages, the CJK multibyte sets) from macrology over ~20,000
;; lines of tables. rontolisp has exactly ONE character model -- a character IS
;; a Unicode code point, and the wire form on every backend is UTF-8 -- so
;; there is nothing for the other 39 encodings to convert BETWEEN here. The
;; shim implements the UTF-8 codec, treats the Latin-1/ASCII aliases as the
;; code-point identity they are for the bytes they can represent, and SIGNALS
;; on any other :encoding rather than silently handing back mis-coded bytes.
;;
;; TWO LAYERS, exactly as upstream. The MAPPING protocol -- lookup-mapping plus
;; the four functions it leads to (code-point-counter, octet-counter, decoder,
;; encoder) -- IS the codec; string-to-octets / octets-to-string /
;; string-size-in-octets are thin drivers over it and own no coding logic of
;; their own. That split is not decoration: a library that decodes
;; INCREMENTALLY has no whole octet vector to hand to octets-to-string, and
;; drives the mapping layer directly instead -- dexador's decoding-stream reads
;; ONE character at a time out of a shared 128-octet buffer, calling the
;; code-point counter with max-chars 1 to find where that character ends and
;; then the decoder over exactly those octets.
;;
;; Two divergences from upstream, both forced by having one character model:
;;
;; - AN ENCODING IS ITS NAME, AND A MAPPING IS ITS ENCODING. Upstream builds a
;;   character-encoding object per charset and a concrete-mapping object per
;;   (encoding, sequence type) pair out of macrology; here the name selects the
;;   codec and there is nothing else for an object to carry, so
;;   get-character-encoding and lookup-mapping normalize their argument and
;;   answer the keyword.
;; - *string-vector-mappings* is therefore the SET of encoding names that have a
;;   mapping rather than a hash table of mapping objects. It is the same list
;;   list-character-encodings answers, so the implemented encodings are named
;;   once in this file.
;;
;; Everything else follows upstream branch for branch: which condition each
;; malformed shape signals, that *suppress-character-coding-errors* replaces the
;; signal with a substitution character (U+FFFD for UTF-8, U+001A for the
;; single-octet encodings, as upstream), and that every driver's :errorp
;; defaults to (not *suppress-character-coding-errors*) and re-binds it.
;;
;; Written in canonical shape; the packages are seeded in PackageRegistry.

(defvar babel-encodings:*default-character-encoding* :utf-8)

;; Read by every decode and encode below, exactly as upstream's is: when it is
;; true a malformed sequence yields the substitution character instead of
;; signalling. dexador's decode-body binds it around the body decode so that a
;; body it cannot decode falls back to binary rather than killing the request.
(defvar babel-encodings:*suppress-character-coding-errors* nil)

;; The encodings that have a mapping -- upstream's *string-vector-mappings*
;; table with the objects left out (see the header).
(defvar babel:*string-vector-mappings* '(:utf-8 :latin-1 :us-ascii))

(defun babel:list-character-encodings ()
  (copy-list babel:*string-vector-mappings*))

;; A caller's :encoding is normalized to one of the three names above. Anything
;; else is an error at the call: mis-coding silently is worse than not coding.
(defun babel::normalize-encoding (encoding)
  (cond ((null encoding) :utf-8)
        ((member encoding '(:utf-8 :utf8 :|utf-8| :default)) :utf-8)
        ((member encoding '(:latin-1 :latin1 :iso-8859-1 :iso8859-1)) :latin-1)
        ((member encoding '(:us-ascii :ascii :usascii)) :us-ascii)
        (t
         (error
          "babel: unsupported character encoding ~S (this build implements ~S)"
          encoding (babel:list-character-encodings)))))

;; Upstream answers a character-encoding OBJECT; here the name IS the object, so
;; this is the normalizer under its babel name -- and it keeps upstream's
;; contract of signalling on a charset this build cannot map.
(defun babel-encodings:get-character-encoding (encoding)
  (babel::normalize-encoding encoding))

;; The buffer-sizing bound an incremental decoder reads before it refills: the
;; longest octet sequence one character can take.
(defun babel-encodings:enc-max-units-per-char (encoding)
  (if (eq (babel::normalize-encoding encoding) :utf-8) 4 1))

;; A character IS a Unicode code point here, so babel's unicode-char -- the
;; :element-type an incremental decoder builds its one-character buffer with,
;; and what such a stream answers for stream-element-type -- is exactly
;; character.
(deftype babel:unicode-char () 'character)

;;; Conditions. Upstream's hierarchy, slot for slot and report for report: the
;;; consumer's fallback path catches character-decoding-error, and the leaves
;;; say which malformed shape was found.

(define-condition babel-encodings:character-coding-error (error)
  ((buffer :initarg :buffer
           :initform nil
           :reader babel-encodings:character-coding-error-buffer)
   (position :initarg :position
             :initform nil
             :reader babel-encodings:character-coding-error-position)
   (encoding :initarg :encoding
             :initform nil
             :reader babel-encodings:character-coding-error-encoding)))

(define-condition babel-encodings:character-decoding-error
    (babel-encodings:character-coding-error)
  ((octets :initarg :octets
           :initform nil
           :reader babel-encodings:character-decoding-error-octets))
  (:report
   (lambda (c s)
     (format s "Illegal ~S character starting at position ~D."
             (babel-encodings:character-coding-error-encoding c)
             (babel-encodings:character-coding-error-position c)))))

(define-condition babel-encodings:end-of-input-in-character
    (babel-encodings:character-decoding-error)
  ())

(define-condition babel-encodings:character-out-of-range
    (babel-encodings:character-decoding-error)
  ())

(define-condition babel-encodings:invalid-utf8-starter-byte
    (babel-encodings:character-decoding-error)
  ())

(define-condition babel-encodings:invalid-utf8-continuation-byte
    (babel-encodings:character-decoding-error)
  ())

(define-condition babel-encodings:overlong-utf8-sequence
    (babel-encodings:character-decoding-error)
  ())

(define-condition babel-encodings:character-encoding-error
    (babel-encodings:character-coding-error)
  ((code :initarg :code
         :initform nil
         :reader babel-encodings:character-encoding-error-code))
  (:report
   (lambda (c s)
     (format s "Unable to encode character code point ~A as ~S."
             (babel-encodings:character-encoding-error-code c)
             (babel-encodings:character-coding-error-encoding c)))))

;; Upstream's decoding-error / encoding-error: signal the precise condition
;; unless the caller suppressed it, in which case the substitution code point is
;; the answer and decoding continues.
(defun babel::%decoding-error (type octets encoding buffer position substitute)
  (unless babel-encodings:*suppress-character-coding-errors*
    (error type
           :octets octets
           :encoding encoding
           :buffer buffer
           :position position))
  substitute)

(defun babel::%encoding-error (code encoding buffer position)
  (unless babel-encodings:*suppress-character-coding-errors*
    (error 'babel-encodings:character-encoding-error
           :code code
           :encoding encoding
           :buffer buffer
           :position position))
  #x1A)

;;; The mapping protocol. lookup-mapping answers the mapping for an encoding;
;;; the four readers answer the functions that mapping codes with. Each is a
;;; closure over the normalized encoding, so a consumer that looks a mapping up
;;; once and calls its counter per character (the incremental case) pays the
;;; normalization once.

(defun babel-encodings:lookup-mapping (mappings encoding)
  (let ((enc (babel::normalize-encoding encoding)))
    (unless (member enc mappings)
      (error "babel: ~S has no mapping in ~S" enc mappings))
    enc))

;; (seq start end max-chars) -> (values chars new-end): how many characters fit
;; in [start, end) -- at most MAX-CHARS of them when that is positive -- and the
;; octet index they end at.
(defun babel-encodings:code-point-counter (mapping)
  (let ((enc (babel::normalize-encoding mapping)))
    (lambda (seq start end max)
      (babel::%count-code-points enc seq start end max))))

;; (string start end max-octets) -> (values octets new-end): the mirror image,
;; counting octets over characters.
(defun babel-encodings:octet-counter (mapping)
  (let ((enc (babel::normalize-encoding mapping)))
    (lambda (seq start end max) (babel::%count-octets enc seq start end max))))

;; (src start end dest dest-start) -> the number of characters written.
(defun babel-encodings:decoder (mapping)
  (let ((enc (babel::normalize-encoding mapping)))
    (lambda (src start end dest dest-start)
      (babel::%decode-into enc src start end dest dest-start))))

;; (src start end dest dest-start) -> the number of octets written.
(defun babel-encodings:encoder (mapping)
  (let ((enc (babel::normalize-encoding mapping)))
    (lambda (src start end dest dest-start)
      (babel::%encode-into enc src start end dest dest-start))))

;;; The codec itself.

(defun babel::%continuation-p (octet) (and (> octet #x7F) (< octet #xC0)))

;; Upstream's invalid-cb-p: the octet N further on exists and is NOT a
;; continuation byte, i.e. the sequence starting here is shorter than its
;; starter byte claims.
(defun babel::%invalid-cb-p (seq end i n)
  (and (< (+ i n) end) (not (babel::%continuation-p (aref seq (+ i n))))))

(defun babel::%count-code-points (encoding seq start end max)
  (cond
        ;; One octet per character: upstream's fixed-width counter.
        ((not (eq encoding :utf-8))
         (let ((n (- end start)))
           (when (and (> max 0) (< max n)) (setq n max))
           (values n (+ start n))))
        (t
         (let ((nchars 0) (i start))
           (do ()
               ((>= i end) (values nchars i))
             (let* ((octet (aref seq i))
                    (width
                     (cond
                      ((or (< octet #xC0) (babel::%invalid-cb-p seq end i 1)) 1)
                      ((or (< octet #xE0) (babel::%invalid-cb-p seq end i 2)) 2)
                      ((or (< octet #xF0) (babel::%invalid-cb-p seq end i 3)) 3)
                      ((or (< octet #xF8) (babel::%invalid-cb-p seq end i 4)) 4)
                      ((or (< octet #xFC) (babel::%invalid-cb-p seq end i 5)) 5)
                      (t 6)))
                    (next-i (+ i width)))
               (cond ((> next-i end)
                      ;; A sequence truncated by the end of the buffer still counts
                      ;; as the one character the decoder will write for it.
                      (babel::%decoding-error
                       'babel-encodings:end-of-input-in-character (list octet)
                       encoding seq i 0)
                      (return (values (+ nchars 1) end)))
                     (t
                      (setq nchars (+ nchars 1))
                      (setq i next-i)
                      (when (and (> max 0) (= nchars max))
                        (return (values nchars i)))))))))))

(defun babel::%count-octets (encoding string start end max)
  (cond ((not (eq encoding :utf-8))
         (let ((n (- end start)))
           (when (and (> max 0) (< max n)) (setq n max))
           (values n (+ start n))))
        (t (let ((noctets 0) (i start))
             (do ()
                 ((>= i end) (values noctets i))
               (let* ((code (char-code (char string i)))
                      (new
                       (+ noctets
                          (cond ((< code #x80) 1)
                                ((< code #x800) 2)
                                ((< code #x10000) 3)
                                (t 4)))))
                 (when (and (> max 0) (> new max)) (return (values noctets i)))
                 (setq noctets new)
                 (setq i (+ i 1))))))))

;; Decode the one character starting at START, answering its code point and the
;; index after it. Upstream's :utf-8 decoder, branch for branch -- including
;; which condition each malformed shape signals and how many octets it consumes,
;; which is what keeps this function and the code-point counter above agreeing
;; on where a character ends.
(defun babel::%utf-8-decode-1 (src start end)
  (let ((u1 (aref src start)))
    (cond ((< u1 #x80) (values u1 (+ start 1)))
          ((< u1 #xC0)
           (values (babel::%decoding-error
                    'babel-encodings:invalid-utf8-starter-byte (list u1)
                    :utf-8 src start #xFFFD) (+ start 1)))
          ((>= (+ start 1) end)
           (values (babel::%decoding-error
                    'babel-encodings:end-of-input-in-character (list u1)
                    :utf-8 src start #xFFFD) end))
          (t (let ((u2 (aref src (+ start 1))))
               (cond ((not (babel::%continuation-p u2))
                      (values (babel::%decoding-error
                               'babel-encodings:invalid-utf8-continuation-byte
                               (list u1)
                               :utf-8 src start #xFFFD) (+ start 1)))
                     ((< u1 #xC2)
                      (values (babel::%decoding-error
                               'babel-encodings:overlong-utf8-sequence
                               (list u1 u2)
                               :utf-8 src start #xFFFD) (+ start 2)))
                     ((< u1 #xE0)
                      (values (logior (ash (logand u1 #x1F) 6) (logand u2 #x3F))
                              (+ start 2)))
                     ((>= (+ start 2) end)
                      (values (babel::%decoding-error
                               'babel-encodings:end-of-input-in-character
                               (list u1 u2)
                               :utf-8 src start #xFFFD) end))
                     (t (let ((u3 (aref src (+ start 2))))
                          (cond
                           ((not (babel::%continuation-p u3))
                            (values
                             (babel::%decoding-error
                              'babel-encodings:invalid-utf8-continuation-byte
                              (list u1 u2)
                              :utf-8 src start #xFFFD) (+ start 2)))
                           ((and (= u1 #xE0) (< u2 #xA0))
                            (values (babel::%decoding-error
                                     'babel-encodings:overlong-utf8-sequence
                                     (list u1 u2 u3)
                                     :utf-8 src start #xFFFD) (+ start 3)))
                           ((< u1 #xF0)
                            (let ((code
                                   (logior (ash (logand u1 #x0F) 12)
                                           (ash (logand u2 #x3F) 6)
                                           (logand u3 #x3F))))
                              ;; A surrogate half is not a character.
                              (if (and (>= code #xD800) (<= code #xDFFF))
                                  (values
                                   (babel::%decoding-error
                                    'babel-encodings:character-out-of-range
                                    (list u1 u2 u3)
                                    :utf-8 src start #xFFFD) (+ start 3))
                                  (values code (+ start 3)))))
                           ((>= (+ start 3) end)
                            (values (babel::%decoding-error
                                     'babel-encodings:end-of-input-in-character
                                     (list u1 u2 u3)
                                     :utf-8 src start #xFFFD) end))
                           (t
                            (let ((u4 (aref src (+ start 3))))
                              (cond
                               ((not (babel::%continuation-p u4))
                                (values (babel::%decoding-error 'babel-encodings:invalid-utf8-continuation-byte
                                                                (list u1 u2 u3)
                                                                :utf-8 src start
                                                                #xFFFD)
                                        (+ start 3)))
                               ((and (= u1 #xF0) (< u2 #x90))
                                (values (babel::%decoding-error
                                         'babel-encodings:overlong-utf8-sequence
                                         (list u1 u2 u3 u4)
                                         :utf-8 src start #xFFFD) (+ start 4)))
                               ((< u1 #xF8)
                                ;; Past U+10FFFF there is no character to decode to.
                                (if (or (> u1 #xF4)
                                        (and (= u1 #xF4) (> u2 #x8F)))
                                    (values
                                     (babel::%decoding-error
                                      'babel-encodings:character-out-of-range
                                      (list u1 u2 u3 u4)
                                      :utf-8 src start #xFFFD) (+ start 4))
                                    (values (logior (ash (logand u1 #x07) 18)
                                                    (ash (logand u2 #x3F) 12)
                                                    (ash (logand u3 #x3F) 6)
                                                    (logand u4 #x3F))
                                            (+ start 4))))
                               ;; The 5- and 6-octet forms UTF-8 never had: consumed whole so
                               ;; that the counter's width and this decoder stay in step.
                               ((>= (+ start 4) end)
                                (values
                                 (babel::%decoding-error
                                  'babel-encodings:end-of-input-in-character
                                  (list u1 u2 u3 u4)
                                  :utf-8 src start #xFFFD) end))
                               ((not
                                 (babel::%continuation-p
                                  (aref src (+ start 4))))
                                (values (babel::%decoding-error
                                         'babel-encodings:invalid-utf8-continuation-byte
                                         (list u1 u2 u3 u4)
                                         :utf-8 src start #xFFFD) (+ start 4)))
                               ((< u1 #xFC)
                                (values (babel::%decoding-error
                                         'babel-encodings:character-out-of-range
                                         (list u1 u2 u3 u4)
                                         :utf-8 src start #xFFFD) (+ start 5)))
                               ((>= (+ start 5) end)
                                (values
                                 (babel::%decoding-error
                                  'babel-encodings:end-of-input-in-character
                                  (list u1 u2 u3 u4)
                                  :utf-8 src start #xFFFD) end))
                               ((not
                                 (babel::%continuation-p
                                  (aref src (+ start 5))))
                                (values (babel::%decoding-error
                                         'babel-encodings:invalid-utf8-continuation-byte
                                         (list u1 u2 u3 u4)
                                         :utf-8 src start #xFFFD) (+ start 5)))
                               (t
                                (values (babel::%decoding-error
                                         'babel-encodings:character-out-of-range
                                         (list u1 u2 u3 u4)
                                         :utf-8 src start #xFFFD)
                                        (+ start 6)))))))))))))))

(defun babel::%decode-into (encoding src start end dest dest-start)
  (let ((i start) (di dest-start))
    (do ()
        ((>= i end) (- di dest-start))
      (cond ((eq encoding :utf-8)
             (multiple-value-bind (code next) (babel::%utf-8-decode-1 src i end)
               (setf (aref dest di) (code-char code))
               (setq i next)))
            (t (let ((octet (aref src i)))
                 (when (and (eq encoding :us-ascii) (> octet #x7F))
                   (setq octet
                         (babel::%decoding-error
                          'babel-encodings:character-decoding-error (list octet)
                          encoding src i #x1A)))
                 (setf (aref dest di) (code-char octet))
                 (setq i (+ i 1)))))
      (setq di (+ di 1)))))

(defun babel::%encode-into (encoding src start end dest dest-start)
  (let ((di dest-start))
    (do ((i start (+ i 1)))
        ((>= i end) (- di dest-start))
      (let ((code (char-code (char src i))))
        (cond ((not (eq encoding :utf-8))
               (when (>= code (if (eq encoding :us-ascii) 128 256))
                 (setq code (babel::%encoding-error code encoding src i)))
               (setf (aref dest di) code)
               (setq di (+ di 1)))
              ((< code #x80)
               (setf (aref dest di) code)
               (setq di (+ di 1)))
              ((< code #x800)
               (setf (aref dest di) (logior #xC0 (ash code -6)))
               (setf (aref dest (+ di 1)) (logior #x80 (logand code #x3F)))
               (setq di (+ di 2)))
              ((< code #x10000)
               (setf (aref dest di) (logior #xE0 (ash code -12)))
               (setf (aref dest (+ di 1))
                     (logior #x80 (logand (ash code -6) #x3F)))
               (setf (aref dest (+ di 2)) (logior #x80 (logand code #x3F)))
               (setq di (+ di 3)))
              (t
               (setf (aref dest di) (logior #xF0 (ash code -18)))
               (setf (aref dest (+ di 1))
                     (logior #x80 (logand (ash code -12) #x3F)))
               (setf (aref dest (+ di 2))
                     (logior #x80 (logand (ash code -6) #x3F)))
               (setf (aref dest (+ di 3)) (logior #x80 (logand code #x3F)))
               (setq di (+ di 4))))))))

;;; The drivers. Each one binds *suppress-character-coding-errors* from its
;;; :errorp exactly as upstream does, so the codec above is the only place that
;;; decides what a malformed sequence costs.

(defun babel:string-to-octets (string &key encoding (start 0) end
                                      (errorp
                                       (not
                                        babel-encodings:*suppress-character-coding-errors*)))
  (let ((enc (babel::normalize-encoding encoding))
        (limit (or end (length string)))
        (babel-encodings:*suppress-character-coding-errors* (not errorp)))
    (multiple-value-bind (size new-end)
        (babel::%count-octets enc string start limit -1)
      ;; A PACKED (unsigned-byte 8) array, like real babel's simple-array
      ;; result: an adjustable array does not carry its declared element type
      ;; here, and a caller's (typep x 'simple-byte-vector) tests exactly that.
      (let ((out (make-array size :element-type '(unsigned-byte 8))))
        (babel::%encode-into enc string start new-end out 0)
        out))))

;; Upstream answers the octet counter's TWO values (the count and the index it
;; stopped at); this answers the count alone, because rontolisp carries a
;; secondary value in a global channel and no consumer has ever wanted the
;; index (see the multiple-values notes in .kb). :max is upstream's -- stop
;; counting before exceeding that many octets.
(defun babel:string-size-in-octets (string &key encoding (start 0) end (max -1)
                                           (errorp
                                            (not
                                             babel-encodings:*suppress-character-coding-errors*)))
  (let ((enc (babel::normalize-encoding encoding))
        (limit (or end (length string)))
        (babel-encodings:*suppress-character-coding-errors* (not errorp)))
    (multiple-value-bind (size new-end)
        (babel::%count-octets enc string start limit max)
      size)))

;; :errorp defaults to (not *suppress-character-coding-errors*) like upstream's.
;; When it is nil a malformed sequence yields the substitution character instead
;; of signalling -- quri's :lenient url-decode is the caller that wants that.
(defun babel:octets-to-string (octets &key encoding (start 0) end
                                      (errorp
                                       (not
                                        babel-encodings:*suppress-character-coding-errors*)))
  (let ((enc (babel::normalize-encoding encoding))
        (limit (or end (length octets)))
        (babel-encodings:*suppress-character-coding-errors* (not errorp)))
    (multiple-value-bind (size new-end)
        (babel::%count-code-points enc octets start limit -1)
      (let ((string (make-string size :element-type 'babel:unicode-char)))
        (babel::%decode-into enc octets start new-end string 0)
        string))))
