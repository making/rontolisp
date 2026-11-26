;; meta-demo.asd loads meta-demo-tool while the .asd is read; on a compile path
;; that happens in the build image, and the program has the tool only because
;; the load counts as a :defsystem-depends-on of the systems defined after it.
;; No in-package on purpose: every backend reads this file in the package that
;; was current when meta-demo.asd began loading, not in the .asd's own.
(defun tool-fn () :tool)
