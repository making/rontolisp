;; The component after one.lisp, read with the feature one.lisp pushed.
(defun two-read () #+lc-demo-one :one-seen #-lc-demo-one :one-unseen)
