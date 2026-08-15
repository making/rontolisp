;;;; System package-inferred-demo/util/text -- a NESTED sub-system name, so the file
;;;; sits at util/text.lisp. Nothing in the .asd mentions it; main.lisp's defpackage is
;;;; the only edge that reaches it. It also opens the way rove's sources do, with an
;;;; (in-package #:cl-user) header BEFORE the defpackage: the derivation skips forms
;;;; until the package definition form, like real ASDF's file-defpackage-form.

(in-package #:cl-user)

(defpackage #:package-inferred-demo/util/text
  (:use #:cl)
  (:export #:shout))
(in-package #:package-inferred-demo/util/text)

(defun shout (text)
  (string-upcase text))
