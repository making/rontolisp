;; Loaded by (load "helper.lisp") from one.lisp: the pathname is the spelling
;; load was called with, the truename the path it resolved to.
(defparameter *helper-pathname* *load-pathname*)
(defparameter *helper-truename* *load-truename*)

;; Read time inside a NESTED load: the innermost file wins, and one.lisp's
;; context comes back after this file ends.
(defparameter *helper-read-time* #.(format nil "~A|~A" *load-pathname* *load-truename*))
