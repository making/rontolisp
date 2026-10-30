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
