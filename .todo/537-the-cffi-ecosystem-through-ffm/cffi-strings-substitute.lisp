;;;; SPIKE: a leaf-module substitution for cffi's strings.lisp -- the same
;;;; surface, without babel's instantiate-concrete-mappings code generator.

(in-package #:cffi)


(defvar *default-foreign-encoding* :utf-8
  "Default foreign encoding.")

(defun null-terminator-len (encoding)
  (declare (ignore encoding))
  1)

(defun %octets (string encoding)
  (babel:string-to-octets string :encoding (or encoding *default-foreign-encoding*)))

(defun %string (octets encoding)
  (babel:octets-to-string octets :encoding (or encoding *default-foreign-encoding*)))

(defun lisp-string-to-foreign (string buffer bufsize &key (start 0) end offset
                                                          (encoding *default-foreign-encoding*))
  (when offset (setf buffer (inc-pointer buffer offset)))
  (let* ((octets (%octets (subseq string start end) encoding))
         (count (min (length octets) (1- bufsize))))
    (dotimes (i count)
      (%mem-set (aref octets i) buffer :unsigned-char i))
    (%mem-set 0 buffer :unsigned-char count)
    buffer))

(defun foreign-string-length (pointer &key (encoding *default-foreign-encoding*) (offset 0))
  (declare (ignore encoding))
  (loop for i from offset
        until (zerop (%mem-ref pointer :unsigned-char i))
        finally (return (- i offset))))

(defun foreign-string-to-lisp (pointer &key (offset 0) count
                                            (max-chars (1- array-total-size-limit))
                                            (encoding *default-foreign-encoding*))
  (declare (ignore max-chars))
  (unless (null-pointer-p pointer)
    (let* ((length (or count (foreign-string-length pointer :offset offset)))
           (octets (make-array length :element-type '(unsigned-byte 8))))
      (dotimes (i length)
        (setf (aref octets i) (%mem-ref pointer :unsigned-char (+ offset i))))
      (values (%string octets encoding) length))))

(defun foreign-string-alloc (string &key (encoding *default-foreign-encoding*)
                                         (null-terminated-p t) (start 0) end)
  (let* ((octets (%octets (subseq string start end) encoding))
         (length (length octets))
         (size (+ length (if null-terminated-p 1 0)))
         (pointer (%foreign-alloc size)))
    (dotimes (i length)
      (%mem-set (aref octets i) pointer :unsigned-char i))
    (when null-terminated-p
      (%mem-set 0 pointer :unsigned-char length))
    (values pointer size)))

(defun foreign-string-free (ptr)
  (foreign-free ptr))

(defmacro with-foreign-string ((var-or-vars lisp-string &rest args) &body body)
  (destructuring-bind (var &optional size-var)
      (alexandria:ensure-list var-or-vars)
    (alexandria:with-gensyms (s)
      `(let ((,s ,lisp-string))
         (multiple-value-bind (,var ,@(when size-var (list size-var)))
             (foreign-string-alloc ,s ,@args)
           (unwind-protect (progn ,@body)
             (foreign-string-free ,var)))))))

(defmacro with-foreign-strings (bindings &body body)
  (if bindings
      `(with-foreign-string ,(car bindings)
         (with-foreign-strings ,(cdr bindings) ,@body))
      `(progn ,@body)))

(defmacro with-foreign-pointer-as-string ((var size &optional size-var &rest args) &body body)
  `(with-foreign-pointer (,var ,size ,size-var)
     (progn
       ,@body
       (values (foreign-string-to-lisp ,var ,@args)))))

;;;# Automatic Conversion of Foreign Strings

(define-foreign-type foreign-string-type ()
  ((encoding :initform nil :initarg :encoding :reader encoding)
   (free-from-foreign :initarg :free-from-foreign
                      :reader fst-free-from-foreign-p
                      :initform nil :type boolean)
   (free-to-foreign :initarg :free-to-foreign
                    :reader fst-free-to-foreign-p
                    :initform t :type boolean))
  (:actual-type :pointer)
  (:simple-parser :string))

(defun fst-encoding (type)
  (or (encoding type) *default-foreign-encoding*))

(defmethod translate-to-foreign ((s string) (type foreign-string-type))
  (values (foreign-string-alloc s :encoding (fst-encoding type))
          (fst-free-to-foreign-p type)))

(defmethod translate-to-foreign (obj (type foreign-string-type))
  (cond ((pointerp obj) (values obj nil))
        (t (error "~A is not a Lisp string or pointer." obj))))

(defmethod translate-from-foreign (ptr (type foreign-string-type))
  (unwind-protect
       (values (foreign-string-to-lisp ptr :encoding (fst-encoding type)))
    (when (fst-free-from-foreign-p type)
      (foreign-free ptr))))

(defmethod free-translated-object (ptr (type foreign-string-type) free-p)
  (when free-p
    (foreign-string-free ptr)))

;;;# STRING+PTR

(define-foreign-type foreign-string+ptr-type (foreign-string-type)
  ()
  (:simple-parser :string+ptr))

(defmethod translate-from-foreign (value (type foreign-string+ptr-type))
  (list (call-next-method) value))

;;; SPIKE: a CL builtin rontolisp does not have yet.
(defun subsetp (list1 list2 &key (test #'eql) key)
  (every (lambda (x)
           (member (if key (funcall key x) x) list2 :test test :key key))
         list1))
