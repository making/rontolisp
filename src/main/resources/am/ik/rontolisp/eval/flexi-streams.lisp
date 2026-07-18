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
(defun flexi-streams:string-to-octets (string &key (external-format :utf-8) (start 0) end)
  (declare (ignore external-format))
  (let ((limit (or end (length string)))
        (out (make-array 0 :element-type '(unsigned-byte 8) :adjustable t :fill-pointer 0)))
    (do ((i start (+ i 1)))
        ((>= i limit) out)
      (let ((c (char-code (char string i))))
        (cond ((< c #x80) (vector-push-extend c out))
              ((< c #x800)
               (vector-push-extend (logior #xC0 (ash c -6)) out)
               (vector-push-extend (logior #x80 (logand c #x3F)) out))
              ((< c #x10000)
               (vector-push-extend (logior #xE0 (ash c -12)) out)
               (vector-push-extend (logior #x80 (logand (ash c -6) #x3F)) out)
               (vector-push-extend (logior #x80 (logand c #x3F)) out))
              (t
               (vector-push-extend (logior #xF0 (ash c -18)) out)
               (vector-push-extend (logior #x80 (logand (ash c -12) #x3F)) out)
               (vector-push-extend (logior #x80 (logand (ash c -6) #x3F)) out)
               (vector-push-extend (logior #x80 (logand c #x3F)) out)))))))

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
