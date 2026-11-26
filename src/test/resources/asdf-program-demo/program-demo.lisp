(defpackage #:program-demo
  (:use #:cl)
  (:export #:main))

(in-package #:program-demo)

;; Read while the system loads, which a dumped program does before it restores.
(defvar *dumped-at-load* uiop:*image-dumped-p*)

;; The entry point's first value is the exit status: true is 0, nil is 1.
(defun main ()
  (format t "arguments ~s~%" (uiop:command-line-arguments))
  (format t "dumped ~s, at load ~s~%" uiop:*image-dumped-p* *dumped-at-load*)
  (not (member "fail" (uiop:command-line-arguments) :test #'string=)))
