;; A component of lc-demo. It is loaded by its RESOLVED path (that is what ASDF
;; hands load), so both load-context variables hold asdf:component-pathname.
(defparameter *one-pathname* *load-pathname*)
(defparameter *one-truename* *load-truename*)

;; The same context read at READ time: a #. datum runs while the file is being
;; read, before any top-level form of it has run, and must still see the file it
;; is written in.
(defparameter *one-read-time* #.(format nil "~A|~A" *load-pathname* *load-truename*))

;; The portable "read a data file that ships beside my source" idiom -- the one
;; real libraries spell, and the reason the read-time context has to be there.
;; *compile-file-pathname* is nil here (there is no compile-file), so the (or ...)
;; falls through to *load-truename*.
(defparameter *one-data*
  #.(with-open-file (f (merge-pathnames "data.lisp-expr"
                                        (or *compile-file-pathname* *load-truename*)))
      (read f)))

;; A nested load inside the component: its own context is pushed for the
;; duration and this file's is back afterwards.
(load "helper.lisp")

(defparameter *one-pathname-after* *load-pathname*)

;; Read at CALL time, so it answers whatever is current then -- nil once the
;; load has finished.
(defun one-context () (list *load-pathname* *load-truename*))
