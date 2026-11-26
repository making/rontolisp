(defun one-fn () 1)

;; Read-time proof that the :defsystem-depends-on entry's announcement reached
;; this component file: trivial-features is what declares :unix here.
(defun platform ()
  #+unix :unix
  #-unix :not-announced)

;; coalton's eclector probe: a feature this file's compile pushes from what ASDF
;; answers about a system the build registered, which the compile paths' read-time
;; image takes from their registry.
(eval-when (:compile-toplevel)
  (if (uiop:version< (asdf:component-version (asdf:find-system "meta-demo")) "1.0.0")
      (pushnew :meta-demo-old *features*)
      (pushnew :meta-demo-new *features*)))

;; Read-time proof that the feature meta-demo.asd COMPUTED and pushed reached
;; the system defined after the push, and that the probe above ran before the read.
(defun build ()
  (list #+meta-demo-small :small
        #-meta-demo-small :not-pushed
        #+meta-demo-new :new
        #+meta-demo-old :old))
