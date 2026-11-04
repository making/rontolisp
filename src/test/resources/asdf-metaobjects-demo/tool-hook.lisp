;; An :around-compile hook by name (mgl-pax.asdf's shape). ASDF's thunk takes
;; compile-file's keyword flags.
(defpackage :meta-demo-tool-hook
  (:use :cl)
  (:export #:call-with-tool-feature))

(in-package :meta-demo-tool-hook)

(defun call-with-tool-feature (compile)
  (let ((*features* (cons :meta-demo-tool-hooked *features*)))
    (funcall compile :verbose nil)))
