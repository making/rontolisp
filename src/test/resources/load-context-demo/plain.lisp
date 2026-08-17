;; Loaded by a plain top-level (load "...") from the exercise program.
(defparameter *plain-pathname* *load-pathname*)
(defparameter *plain-truename* *load-truename*)

;; Read time in a plain (non-component) load.
(defparameter *plain-read-time* #.(format nil "~A|~A" *load-pathname* *load-truename*))
