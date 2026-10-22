;;;; embed.lisp -- the files a deck names: code read as text, images inlined as data: URIs.
;;;;
;;;; RENDER does no I/O, so the host passes the function that reads a file's bytes. Code
;;;; is decoded from UTF-8 here; an image's MIME type comes from the extension, and its
;;;; base64 is computed here, so every backend produces the same characters.

(in-package :lispresen)

(defvar *read-file* nil
  "The host's function from a path the deck names to the file's bytes (nil when it cannot
be read), or nil when the host reads no files.")

(defun extension (path)
  "The extension of the file PATH, or nil."
  (let ((dot (position #\. path :from-end t)))
    (and dot (not (find #\/ path :start dot)) (subseq path (1+ dot)))))

(defun utf-8-string (octets)
  "OCTETS decoded as UTF-8 without a leading byte order mark, or nil when they are not
UTF-8."
  (let ((n (length octets)) (i 0))
    (when (and (>= n 3) (= (aref octets 0) #xEF) (= (aref octets 1) #xBB)
               (= (aref octets 2) #xBF))
      (setf i 3))
    (block decode
      (with-output-to-string (out)
        (loop while (< i n)
              do
                (let* ((b (aref octets i))
                       ;; K continuation bytes follow the lead byte B.
                       (k
                        (cond ((< b #x80) 0)
                              ((< b #xC2) (return-from decode nil))
                              ((< b #xE0) 1)
                              ((< b #xF0) 2)
                              ((< b #xF5) 3)
                              (t (return-from decode nil))))
                       (code (if (= k 0) b (logand b (ash #x3F (- k))))))
                  (when (>= (+ i k) n) (return-from decode nil))
                  (loop for j from 1 to k
                        do
                          (let ((c (aref octets (+ i j))))
                            (unless (= (logand c #xC0) #x80)
                              (return-from decode nil))
                            (setf code (logior (ash code 6) (logand c #x3F)))))
                  (when (or (and (= k 2) (< code #x800))
                            (and (= k 3) (or (< code #x10000) (> code #x10FFFF)))
                            (<= #xD800 code #xDFFF))
                    (return-from decode nil))
                  (write-char (code-char code) out)
                  (incf i (1+ k))))))))

(defparameter *code-langs*
  '(("lisp" . "lisp") ("lsp" . "lisp") ("cl" . "lisp") ("scm" . "scheme")
    ("ss" . "scheme") ("clj" . "clojure") ("cljc" . "clojure"))
  "Code file extensions and the language they default :lang to.")

(defun code-file-lang (path)
  "The language of the code file PATH by its extension, or nil."
  (let ((ext (extension path)))
    (and ext (cdr (assoc ext *code-langs* :test #'string-equal)))))

(defun code-file-text (path range)
  "The text of the code file PATH, or of its lines FIRST to LAST when RANGE is (FIRST
LAST); nil after reporting when the file cannot be read or is not UTF-8."
  (let* ((octets (and *read-file* (funcall *read-file* path)))
         (text (and octets (utf-8-string octets))))
    (cond ((null octets)
           (report :error (format nil "cannot read code file ~a" path))
           nil)
          ((null text)
           (report :error (format nil "code file ~a is not UTF-8" path))
           nil)
          ((null range) text)
          (t
           (let* ((lines (split-lines text))
                  (count
                   (if (equal (car (last lines)) "")
                       (1- (length lines))
                       (length lines))))
             (when (> (second range) count)
               (report :error (format nil
                                      ":lines ~d-~d runs past the ~d lines of ~a"
                                      (first range) (second range) count path)))
             (format nil "~{~a~^~%~}"
                     (subseq lines (min (1- (first range)) count)
                             (min (second range) count))))))))

(defparameter *image-types*
  '(("png" . "image/png") ("jpg" . "image/jpeg") ("jpeg" . "image/jpeg")
    ("gif" . "image/gif") ("webp" . "image/webp") ("avif" . "image/avif")
    ("svg" . "image/svg+xml"))
  "Image file extensions and their MIME types.")

(defun image-type (src)
  "The MIME type of the image file SRC by its extension, or nil."
  (let ((ext (extension src)))
    (and ext (cdr (assoc ext *image-types* :test #'string-equal)))))

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

(defun embed-figure (figure)
  "Replaces the source of FIGURE with a data: URI of the image file's bytes."
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
           (let ((octets (and *read-file* (funcall *read-file* src))))
             (if octets
                 (setf (figure-src figure)
                  (concatenate 'string "data:" type ";base64," (base64 octets)))
                 (report :error (format nil
                                        "cannot embed image ~a: cannot read it"
                                        src))))))))

(defun embed-images (slide)
  "Embeds every image of SLIDE, inside columns too."
  (dolist (b (region-blocks (slide-blocks slide)))
    (when (figure-p b) (embed-figure b))))
