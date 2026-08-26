;; A leaf-module substitution for upstream cffi's src/strings.lisp -- the ONE
;; portable file of the system that cannot load here. The original drives
;; babel's instantiate-concrete-mappings code generator, which emits a
;; per-encoding accessor pair for every encoding babel knows; the babel shim
;; (babel.lisp beside this file) has the two bulk converters and not the
;; generator. So the same surface is rebuilt over babel:string-to-octets /
;; babel:octets-to-string, which the shim does have.
;;
;; Everything the file exports is here: *default-foreign-encoding*,
;; foreign-string-alloc / -free / -to-lisp, lisp-string-to-foreign,
;; foreign-string-length, with-foreign-string(s),
;; with-foreign-pointer-as-string, and the :string and :string+ptr foreign
;; types with their translate/expand methods -- which is what makes a
;; (defcfun "getenv" :string (name :string)) work.
;;
;; The one behavioral difference: an encoding is honored only as far as babel's
;; shim honors it, and the NUL terminator is always one octet. Every encoding
;; the shim carries is ASCII-compatible, so that is the terminator length for
;; all of them; a UTF-16 foreign string would need two and is not reachable
;; here.

(in-package #:cffi)

(defvar *default-foreign-encoding* :utf-8 "Default foreign encoding.")

(defun null-terminator-len (encoding)
  "The length in octets of a null terminator in ENCODING.  Always 1: every
encoding the babel shim carries is ASCII-compatible."
  (declare (ignore encoding))
  1)

(defun %string-octets (string encoding)
  (babel:string-to-octets string
                          :encoding (or encoding *default-foreign-encoding*)))

(defun %octets-string (octets encoding)
  (babel:octets-to-string octets
                          :encoding (or encoding *default-foreign-encoding*)))

(defun lisp-string-to-foreign (string buffer bufsize &key (start 0) end offset
                                      (encoding *default-foreign-encoding*))
  "Copy at most BUFSIZE octets from STRING into BUFFER, null-terminated."
  (when offset (setf buffer (inc-pointer buffer offset)))
  (let* ((octets (%string-octets (subseq string start end) encoding))
         (count (min (length octets) (1- bufsize))))
    (dotimes (i count) (%mem-set (aref octets i) buffer :unsigned-char i))
    (%mem-set 0 buffer :unsigned-char count)
    buffer))

(defun foreign-string-length
    (pointer &key (encoding *default-foreign-encoding*) (offset 0))
  "The length in octets of the null-terminated foreign string at POINTER plus
OFFSET octets."
  (declare (ignore encoding))
  (do ((i 0 (1+ i)))
      ((zerop (%mem-ref pointer :unsigned-char (+ offset i))) i)))

(defun foreign-string-to-lisp (pointer &key (offset 0) count
                                       (max-chars (1- array-total-size-limit))
                                       (encoding *default-foreign-encoding*))
  "Copy the foreign string at POINTER plus OFFSET octets into a Lisp string.
Answers nil for a null pointer, as upstream does."
  (declare (ignore max-chars))
  (unless (null-pointer-p pointer)
    (let* ((length (or count (foreign-string-length pointer :offset offset)))
           (octets (make-array length :element-type '(unsigned-byte 8))))
      (dotimes (i length)
        (setf (aref octets i) (%mem-ref pointer :unsigned-char (+ offset i))))
      (values (%octets-string octets encoding) length))))

(defun foreign-string-alloc (string &key (encoding *default-foreign-encoding*)
                                    (null-terminated-p t) (start 0) end)
  "Allocate a foreign string containing STRING; answers the pointer and the
number of octets allocated."
  (let* ((octets (%string-octets (subseq string start end) encoding))
         (length (length octets))
         (size (+ length (if null-terminated-p 1 0)))
         (pointer (%foreign-alloc size)))
    (dotimes (i length) (%mem-set (aref octets i) pointer :unsigned-char i))
    (when null-terminated-p (%mem-set 0 pointer :unsigned-char length))
    (values pointer size)))

(defun foreign-string-free (ptr)
  "Free a foreign string allocated by FOREIGN-STRING-ALLOC."
  (foreign-free ptr))

(defmacro with-foreign-string ((var-or-vars lisp-string &rest args) &body body)
  "Bind VAR to a foreign string containing LISP-STRING for the extent of BODY."
  (destructuring-bind (var &optional size-var)
      (alexandria:ensure-list var-or-vars)
    (alexandria:with-gensyms (string)
      `(let ((,string ,lisp-string))
         (multiple-value-bind (,var ,@(when size-var (list size-var)))
             (foreign-string-alloc ,string ,@args)
           (unwind-protect (progn ,@body) (foreign-string-free ,var)))))))

(defmacro with-foreign-strings (bindings &body body)
  (if bindings
      `(with-foreign-string ,(car bindings)
         (with-foreign-strings ,(cdr bindings) ,@body))
      `(progn ,@body)))

(defmacro with-foreign-pointer-as-string
    ((var size &optional size-var &rest args) &body body)
  "Bind VAR to SIZE bytes of foreign memory for the extent of BODY, then
answer the foreign string it holds."
  `(with-foreign-pointer (,var ,size ,size-var)
     (progn
       ,@body
       (values (foreign-string-to-lisp ,var ,@args)))))

;;;# Automatic Conversion of Foreign Strings

(define-foreign-type foreign-string-type ()
  ((encoding :initform nil :initarg :encoding :reader encoding)
   (free-from-foreign :initarg :free-from-foreign
                      :reader fst-free-from-foreign-p
                      :initform nil
                      :type boolean)
   (free-to-foreign :initarg :free-to-foreign
                    :reader fst-free-to-foreign-p
                    :initform t
                    :type boolean))
  (:actual-type :pointer)
  (:simple-parser :string))

(defun fst-encoding (type) (or (encoding type) *default-foreign-encoding*))

(defmethod translate-to-foreign ((s string) (type foreign-string-type))
  (values (foreign-string-alloc s :encoding (fst-encoding type))
          (fst-free-to-foreign-p type)))

(defmethod translate-to-foreign (obj (type foreign-string-type))
  (cond ((pointerp obj) (values obj nil))
        (t (error "~A is not a Lisp string or pointer." obj))))

(defmethod translate-from-foreign (ptr (type foreign-string-type))
  (unwind-protect (values
                   (foreign-string-to-lisp ptr :encoding (fst-encoding type)))
    (when (fst-free-from-foreign-p type) (foreign-free ptr))))

(defmethod free-translated-object (ptr (type foreign-string-type) free-p)
  (when free-p (foreign-string-free ptr)))

(defmethod expand-to-foreign-dyn-indirect
    (value var body (type foreign-string-type))
  (alexandria:with-gensyms (str)
    (expand-to-foreign-dyn value str
     (list (expand-to-foreign-dyn-indirect str var body (parse-type :pointer)))
     type)))

;;;# STRING+PTR

(define-foreign-type foreign-string+ptr-type (foreign-string-type)
  ()
  (:simple-parser :string+ptr))

(defmethod translate-from-foreign (value (type foreign-string+ptr-type))
  (list (call-next-method) value))
