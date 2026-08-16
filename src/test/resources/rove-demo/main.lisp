(defpackage #:my-app/main
  (:use #:cl)
  (:export #:add
           #:app-error
           #:parse-token))
(in-package #:my-app/main)

(define-condition app-error (error)
  ()
  (:report "Invalid token"))

(defun add (a b)
  (+ a b))

(defun parse-token (s)
  (unless (stringp s)
    (error 'type-error :datum s :expected-type 'string))
  (when (string= s "")
    (error 'app-error))
  s)
