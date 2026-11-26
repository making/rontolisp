;; Loaded by a plain top-level (load "...") from the exercise program.
(defparameter *plain-pathname* *load-pathname*)
(defparameter *plain-truename* *load-truename*)

;; Read time in a plain (non-component) load.
(defparameter *plain-read-time* #.(format nil "~A|~A" *load-pathname* *load-truename*))

;; Read with the feature lc-demo's one.lisp pushed, loaded before this file.
(defparameter *plain-saw-one* #+lc-demo-one :one-seen #-lc-demo-one :one-unseen)

;; A push the program computes decides the read of the file loaded after it.
(pushnew (intern "LC-DEMO-PLAIN" :keyword) *features*)
(load "plain-next.lisp")
