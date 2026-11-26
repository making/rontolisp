(defpackage :meta-hooks (:use :cl) (:export #:*log* #:*current*))
(in-package :meta-hooks)

(defvar *log* nil)
(defvar *current* :global)
(defvar *file-local* '(*current*))

;; asdf-flv's method: every component load gets fresh bindings of the
;; file-local variables, so an assignment in one file stays in that file.
(defmethod asdf:perform :around ((o asdf:load-op) (c asdf:cl-source-file))
  (progv *file-local* (mapcar #'symbol-value *file-local*)
    (call-next-method)))

(defmethod asdf:perform :before ((o asdf:load-op) (c asdf:cl-source-file))
  (push (list :load (asdf:component-name c)) *log*))

(defmethod asdf:perform :after ((o asdf:compile-op) (c asdf:cl-source-file))
  (push (list :compile (asdf:component-name c)) *log*))

(defmethod asdf:perform :after ((o asdf:load-op) (c asdf:system))
  (push (list :system (asdf:component-name c)) *log*))

;; arnesi's asdf.lisp: an operation of its own with an operation-done-p
;; method, and a function naming compile-bundle-op and uiop:copy-file.
(defclass clean-op (asdf:operation)
  ((for-op :accessor for-op :initarg :for-op :initform 'asdf:compile-op)))

(defmethod asdf:operation-done-p ((op clean-op) (c asdf:component))
  (if (asdf:output-files (make-instance (for-op op)) c) nil :clean))

(defun make-single-fasl (system-name &key output-file)
  (asdf:operate 'asdf:compile-bundle-op system-name)
  (when output-file
    (uiop:copy-file (first (asdf:input-files 'asdf:compile-bundle-op system-name)) output-file)))
