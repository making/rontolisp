;;;; uiop/image -- the image / condition-reporting slice. Canonical shape; see .kb/uiop.md.

;; Lite: no backend carries a Lisp-level call stack, so there is no backtrace to
;; print and the honest rendering is the condition alone. Real UIOP's own fallback
;; for an implementation without a backtrace API is the same shape.
;; lack-middleware-backtrace calls it as the first line of its error report.
(defun uiop/image:print-condition-backtrace
    (%pcb-condition &key (stream *error-output*) count)
  (format stream "~A~%" %pcb-condition)
  (values))
