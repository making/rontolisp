;; A component of lc-demo. It is loaded by its RESOLVED path (that is what ASDF
;; hands load), so both load-context variables hold asdf:component-pathname.
(defparameter *one-pathname* *load-pathname*)
(defparameter *one-truename* *load-truename*)

;; A nested load inside the component: its own context is pushed for the
;; duration and this file's is back afterwards.
(load "helper.lisp")

(defparameter *one-pathname-after* *load-pathname*)

;; Read at CALL time, so it answers whatever is current then -- nil once the
;; load has finished.
(defun one-context () (list *load-pathname* *load-truename*))
