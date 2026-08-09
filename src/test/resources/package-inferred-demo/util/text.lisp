;;;; System package-inferred-demo/util/text -- a NESTED sub-system name, so the file
;;;; sits at util/text.lisp. Nothing in the .asd mentions it; main.lisp's defpackage is
;;;; the only edge that reaches it.

(defpackage #:package-inferred-demo/util/text
  (:use #:cl)
  (:export #:shout))
(in-package #:package-inferred-demo/util/text)

(defun shout (text)
  (string-upcase text))
