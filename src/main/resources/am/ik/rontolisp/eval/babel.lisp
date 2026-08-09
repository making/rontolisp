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
;; Written in canonical shape; the packages are seeded in PackageRegistry.

(defvar babel-encodings:*default-character-encoding* :utf-8)

(defun babel:list-character-encodings () (list :utf-8 :latin-1 :us-ascii))

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

(defun babel:string-to-octets (string &key encoding (start 0) end)
  (let ((enc (babel::normalize-encoding encoding))
        (limit (or end (length string)))
        (bytes nil))
    (do ((i start (+ i 1)))
        ((>= i limit))
      (let ((c (char-code (char string i))))
        (cond ((eq enc :utf-8)
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
                      (push (logior #x80 (logand c #x3F)) bytes))))
              ((< c (if (eq enc :us-ascii) 128 256)) (push c bytes))
              (t (error "babel: character ~S is not encodable in ~S"
                        (char string i) enc)))))
    (setq bytes (nreverse bytes))
    ;; A PACKED (unsigned-byte 8) array, like real babel's simple-array result:
    ;; an adjustable array does not carry its declared element type here, and a
    ;; caller's (typep x 'simple-byte-vector) tests exactly that.
    (let ((out (make-array (length bytes) :element-type '(unsigned-byte 8)))
          (i 0))
      (dolist (b bytes)
        (setf (aref out i) b)
        (setq i (+ i 1)))
      out)))

(defun babel:string-size-in-octets (string &key encoding (start 0) end)
  (length
   (babel:string-to-octets string :encoding encoding :start start :end end)))

;; :errorp defaults to t like real babel's. When nil, a malformed sequence
;; yields the replacement character instead of signalling -- quri's :lenient
;; url-decode is the caller that wants that.
(defun babel:octets-to-string (octets &key encoding (start 0) end (errorp t))
  (let ((enc (babel::normalize-encoding encoding))
        (limit (or end (length octets))))
    (with-output-to-string (s)
      (do ((i start))
          ((>= i limit))
        (let ((b (aref octets i)))
          (cond ((not (eq enc :utf-8))
                 (when (and (eq enc :us-ascii) (> b 127))
                   (if errorp
                       (error "babel: octet ~S is not decodable in ~S" b enc)
                       (setq b 65533)))
                 (write-char (code-char b) s)
                 (setq i (+ i 1)))
                ((< b #x80)
                 (write-char (code-char b) s)
                 (setq i (+ i 1)))
                (t
                 (let* ((width
                         (cond ((and (>= b #xC2) (< b #xE0)) 2)
                               ((and (>= b #xE0) (< b #xF0)) 3)
                               ((and (>= b #xF0) (< b #xF5)) 4)
                               (t 0)))
                        (ok (and (> width 0) (<= (+ i width) limit))))
                   (when ok
                     (do ((k 1 (+ k 1)))
                         ((>= k width))
                       (let ((cb (aref octets (+ i k))))
                         (unless (and (>= cb #x80) (< cb #xC0))
                           (setq ok nil)))))
                   (cond ((not ok)
                          (if errorp
                              (error
                               "babel: invalid UTF-8 sequence at position ~S" i)
                              (progn
                                (write-char (code-char 65533) s)
                                (setq i (+ i 1)))))
                         (t
                          (write-char (code-char
                                       (cond ((= width 2)
                                              (logior (ash (logand b #x1F) 6)
                                                      (logand
                                                       (aref octets (+ i 1))
                                                       #x3F)))
                                             ((= width 3)
                                              (logior (ash (logand b #x0F) 12)
                                                      (ash (logand (aref octets
                                                                    (+ i 1))
                                                                   #x3F) 6)
                                                      (logand
                                                       (aref octets (+ i 2))
                                                       #x3F)))
                                             (t
                                              (logior (ash (logand b #x07) 18)
                                                      (ash (logand (aref octets
                                                                    (+ i 1))
                                                                   #x3F) 12)
                                                      (ash (logand (aref octets
                                                                    (+ i 2))
                                                                   #x3F) 6)
                                                      (logand
                                                       (aref octets (+ i 3))
                                                       #x3F))))) s)
                          (setq i (+ i width))))))))))))
