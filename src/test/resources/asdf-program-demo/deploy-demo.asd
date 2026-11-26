;; A deploy application, the shape deploy's own README gives one.
(defsystem "deploy-demo"
  :defsystem-depends-on (:deploy)
  :build-operation "deploy-op"
  :build-pathname "deploy-demo"
  :entry-point "deploy-demo:main"
  :components ((:file "deploy-demo")))
