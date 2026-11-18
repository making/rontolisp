;;;; zip.lisp -- a ZIP archive writer, for the parts of a PPTX package.
;;;;
;;;; One entry per (name . octets), deflated with the fixed-Huffman block of png.lisp. The
;;;; timestamps are the fixed 1980-01-01 00:00 so two renders of one deck write the same
;;;; bytes on every backend; the order is the list's, and nothing here reads the clock.

(in-package :lispresen)

(defun put-u16-le (n out)
  (vector-push-extend (logand n #xFF) out)
  (vector-push-extend (logand (ash n -8) #xFF) out))

(defun put-u32-le (n out)
  (dotimes (k 4) (vector-push-extend (logand (ash n (* -8 k)) #xFF) out)))

(defun ascii-name-p (name)
  (every (lambda (c) (< (char-code c) 128)) name))

(defun entry-octets (content)
  "CONTENT as octets: XML parts are strings, written UTF-8."
  (if (stringp content)
      (coerce (utf-8-octets content) '(vector (unsigned-byte 8)))
      content))

(defun zip-octets (entries)
  "The ZIP archive of ENTRIES, (NAME . OCTETS) lists with ASCII names, in that order."
  (let ((out (octet-buffer))
        (central (octet-buffer))
        (count 0))
    (dolist (entry entries)
      (destructuring-bind (name . content) entry
        (let ((octets (entry-octets content)))
        (unless (ascii-name-p name)
          (error "ZIP entry ~a is not ASCII" name))
        (let* ((data (deflate-raw octets))
               (offset (fill-pointer out))
               (crc (crc32 octets)))
          ;; Local file header: version 20, no flags, deflate, 1980-01-01 00:00.
          (put-u32-le #x04034B50 out)
          (put-u16-le 20 out)
          (put-u16-le 0 out)
          (put-u16-le 8 out)
          (put-u16-le 0 out)
          (put-u16-le #x21 out)
          (put-u32-le crc out)
          (put-u32-le (length data) out)
          (put-u32-le (length octets) out)
          (put-u16-le (length name) out)
          (put-u16-le 0 out)
          (loop for c across name do (vector-push-extend (char-code c) out))
          (loop for b across data do (vector-push-extend b out))
          ;; Central directory entry: the same fields, the local header's offset.
          (put-u32-le #x02014B50 central)
          (put-u16-le 20 central)
          (put-u16-le 20 central)
          (put-u16-le 0 central)
          (put-u16-le 8 central)
          (put-u16-le 0 central)
          (put-u16-le #x21 central)
          (put-u32-le crc central)
          (put-u32-le (length data) central)
          (put-u32-le (length octets) central)
          (put-u16-le (length name) central)
          (put-u16-le 0 central)
          (put-u16-le 0 central)
          (put-u16-le 0 central)
          (put-u16-le 0 central)
          (put-u32-le 0 central)
          (put-u32-le offset central)
          (loop for c across name do (vector-push-extend (char-code c) central))
          (incf count)))))
    (let ((start (fill-pointer out)))
      (loop for b across central do (vector-push-extend b out))
      ;; End of central directory.
      (put-u32-le #x06054B50 out)
      (put-u16-le 0 out)
      (put-u16-le 0 out)
      (put-u16-le count out)
      (put-u16-le count out)
      (put-u32-le (length central) out)
      (put-u32-le start out)
      (put-u16-le 0 out))
    out))
