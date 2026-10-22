;;;; embed.lisp -- images inlined as data: URIs.
;;;;
;;;; RENDER does no I/O, so the host passes the function that reads an image's bytes;
;;;; the MIME type comes from the extension, and the base64 is computed here so every
;;;; backend produces the same characters.

(in-package :lispresen)

(defparameter *image-types*
  '(("png" . "image/png") ("jpg" . "image/jpeg") ("jpeg" . "image/jpeg")
    ("gif" . "image/gif") ("webp" . "image/webp") ("avif" . "image/avif")
    ("svg" . "image/svg+xml"))
  "Image file extensions and their MIME types.")

(defun image-type (src)
  "The MIME type of the image file SRC by its extension, or nil."
  (let ((dot (position #\. src :from-end t)))
    (and dot (not (find #\/ src :start dot))
     (cdr (assoc (subseq src (1+ dot)) *image-types* :test #'string-equal)))))

(defun url-p (src)
  "True when SRC starts with a URL scheme (two or more characters, then a colon)."
  (let ((colon (position #\: src)))
    (and colon (> colon 1) (alpha-char-p (char src 0))
         (every (lambda (c) (or (alphanumericp c) (find c "+-.")))
                (subseq src 0 colon)))))

(defparameter *base64-alphabet*
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")

(defun base64 (octets)
  "OCTETS, a vector of bytes, in padded base64."
  (let ((n (length octets)))
    (with-output-to-string (out)
      (loop for i from 0 below n by 3
            do
              (let* ((more (- n i))
                     (w
                      (+ (ash (aref octets i) 16)
                         (if (> more 1) (ash (aref octets (+ i 1)) 8) 0)
                         (if (> more 2) (aref octets (+ i 2)) 0))))
                ;; MORE bytes of the group fill its first MORE + 1 characters.
                (loop for k from 0 to 3
                      do
                        (write-char (if (> k more)
                                        #\=
                                        (char *base64-alphabet*
                                         (logand (ash w (* -6 (- 3 k))) 63)))
                                    out)))))))

(defun embed-figure (figure read)
  "Replaces the source of FIGURE with a data: URI of the bytes READ answers for it."
  (let* ((src (figure-src figure)) (type (image-type src)))
    (cond ((url-p src)
           (unless (string-equal (subseq src 0 (position #\: src)) "data")
             (report :warning (format nil
                                      "image ~a is a URL; only local files are embedded"
                                      src))))
          ((null type)
           (report :error (format nil
                                  "cannot embed image ~a: unknown type; use ~{.~a~^, ~}"
                                  src (mapcar #'car *image-types*))))
          (t
           (let ((octets (funcall read src)))
             (if octets
                 (setf (figure-src figure)
                  (concatenate 'string "data:" type ";base64," (base64 octets)))
                 (report :error (format nil
                                        "cannot embed image ~a: cannot read it"
                                        src))))))))

(defun embed-images (slide read)
  "Embeds every image of SLIDE, inside columns too."
  (dolist (b (region-blocks (slide-blocks slide)))
    (when (figure-p b) (embed-figure b read))))
