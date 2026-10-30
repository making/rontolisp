(defun one-fn () 1)

;; Read-time proof that the :defsystem-depends-on entry's announcement reached
;; this component file: trivial-features is what declares :unix here.
(defun platform ()
  #+unix :unix
  #-unix :not-announced)

;; Read-time proof that the feature meta-demo.asd COMPUTED and pushed reached
;; the system defined after the push.
(defun build ()
  #+meta-demo-small :small
  #-meta-demo-small :not-pushed)
