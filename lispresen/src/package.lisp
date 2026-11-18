;;;; package.lisp -- the lispresen package.

(defpackage :lispresen
  (:use :cl)
  (:export #:render #:render-pptx #:diagnostic-severity #:format-diagnostic #:version-line
           #:starter-deck #:make-served #:served-respond))
