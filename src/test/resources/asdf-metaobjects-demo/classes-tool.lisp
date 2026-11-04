;; One class per consumer shape: prove-asdf's test-file, lass's lass-file,
;; hu.dwim.asdf's system class and source file class, coalton-asdf's classes.
(defpackage :classes-tool
  (:use :cl)
  (:export :later-file :tool-system :run-later :*log*))

(in-package :classes-tool)

(defvar *log* nil)

(defun note (&rest what) (setq *log* (cons what *log*)))

;; test-file: a source file whose load-op leaves it unread and remembers it, loaded
;; when something asks through load-source-op. Its keyword finds it because the class
;; name is imported into asdf.
(defvar *later-files* (make-hash-table))

(defclass later-file (asdf:cl-source-file) ())

(defmethod asdf:perform ((op asdf:compile-op) (c later-file))
  (note :compile-later (asdf:component-name c)))

(defmethod asdf:perform ((op asdf:load-op) (c later-file))
  (note :remember-later (asdf:component-name c))
  (pushnew c (gethash (asdf:component-system c) *later-files*)))

(defun run-later (system)
  (dolist (c (reverse (gethash (asdf:find-system system) *later-files*)))
    (asdf:perform 'asdf:load-source-op c)))

(import 'later-file :asdf)

;; lass-file: no Lisp source, a type from a default-initarg, an option of its own, and
;; a subclass smuggled into asdf for its keyword.
(defclass data-file (asdf:source-file)
  ((output :initarg :output :initform nil :accessor data-output))
  (:default-initargs :type "dat"))

(defclass asdf/interface::data-file (data-file) ())

(defmethod asdf:perform ((op asdf:compile-op) (c data-file))
  (note :compile-data (asdf:component-name c) (data-output c)
        (file-namestring (asdf:component-pathname c))))

(defmethod asdf:perform ((op asdf:load-op) (c data-file))
  (note :load-data (asdf:component-name c)))

;; hu.dwim.cl-source-file: a hook from a default-initarg, a :around on every load.
(defun hooked (thunk)
  (let ((*features* (cons :classes-hooked *features*)))
    (funcall thunk)))

(defclass logged-file (asdf:cl-source-file) ()
  (:default-initargs :around-compile 'hooked))

(defmethod asdf:perform :around ((op asdf:load-op) (c logged-file))
  (note :logged-load (asdf:component-name c))
  (call-next-method))

;; hu.dwim.system: the class of its :file components in a slot's initform, a slot
;; filled when the instance is reinitialized, and an initarg it adds itself.
(defclass tool-system (asdf:system)
  ((package-of :accessor package-of)
   (asdf/component:default-component-class :initform 'logged-file)))

(defmethod reinitialize-instance :after ((system tool-system) &rest args &key &allow-other-keys)
  (declare (ignore args))
  (setf (package-of system) (string-upcase (asdf:component-name system))))

(defmethod shared-initialize :around ((system tool-system) slot-names &rest initargs)
  (unless (getf initargs :license)
    (setf (getf initargs :license) "From the class"))
  (apply #'call-next-method system slot-names initargs))

;; coalton-asdf: extending ASDF from inside its package, the keyword finding the class
;; there, the file type in the slot's initform.
(in-package :asdf)

(defclass typed-file (cl-source-file)
  ((type :initform "tl")))
