;; The flexi-streams package: a lite shim satisfying the built-in ASDF system
;; "flexi-streams". rontolisp streams carry no element type (every stream is a
;; character stream), so a flexi stream wrapper is the underlying stream
;; itself. Written in canonical shape; the package is seeded in
;; PackageRegistry.

(defun flexi-streams:make-flexi-stream (stream &rest args)
  (declare (ignore args))
  stream)

;; UTF-8 is the only external format the shim implements: callers reaching it
;; pass :utf-8 or :default (md5's md5sum-string does), and rontolisp strings
;; hold full code points, so the encoder covers the whole Unicode range.
;; The result is a PACKED (non-adjustable) (unsigned-byte 8) array, like the
;; real flexi-streams' simple-array: an adjustable array does not carry its
;; declared element type here, and md5's etypecase dispatches the result on
;; (typep x '(array (unsigned-byte 8) (*))), which tests the element type.
(defun flexi-streams:string-to-octets (string &key (external-format :utf-8) (start 0) end)
  (declare (ignore external-format))
  (let ((limit (or end (length string)))
        (bytes nil))
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

(defun flexi-streams:octets-to-string (octets &key (external-format :utf-8) (start 0) end)
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
                 (write-char (code-char (logior (ash (logand b #x1F) 6)
                                                (logand (aref octets (+ i 1)) #x3F)))
                             s)
                 (setq i (+ i 2)))
                ((< b #xF0)
                 (write-char (code-char (logior (ash (logand b #x0F) 12)
                                                (ash (logand (aref octets (+ i 1)) #x3F) 6)
                                                (logand (aref octets (+ i 2)) #x3F)))
                             s)
                 (setq i (+ i 3)))
                (t
                 (write-char (code-char (logior (ash (logand b #x07) 18)
                                                (ash (logand (aref octets (+ i 1)) #x3F) 12)
                                                (ash (logand (aref octets (+ i 2)) #x3F) 6)
                                                (logand (aref octets (+ i 3)) #x3F)))
                             s)
                 (setq i (+ i 4)))))))))
