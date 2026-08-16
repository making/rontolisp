(defun one-fn () 1)

;; Read-time proof that the :defsystem-depends-on entry's announcement reached
;; this component file: trivial-features is what declares :unix here.
(defun platform ()
  #+unix :unix
  #-unix :not-announced)
