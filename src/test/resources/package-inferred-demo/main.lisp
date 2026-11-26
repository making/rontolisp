;;;; System package-inferred-demo/main -- the one sub-system the .asd names. Its
;;;; dependencies are read out of this defpackage: the nested sub-system below it and,
;;;; through the .asd's register-system-packages line, the package-inferred-demo-tag
;;;; system that owns pkg.inferred.tag.

(uiop:define-package #:package-inferred-demo
  (:nicknames #:package-inferred-demo/main)
  (:use #:cl)
  (:use-reexport #:package-inferred-demo/util/text)
  (:import-from #:pkg.inferred.tag
                #:tag)
  (:export #:greet))
(in-package #:package-inferred-demo)

(defun greet (name)
  (format nil "~a, ~a! [~a]" (shout "hello") name (tag)))
