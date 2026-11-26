(defpackage #:my-plain
  (:use #:cl)
  (:export #:greet))
(in-package #:my-plain)

(defun greet (name)
  (format nil "Hello, ~A!" name))
