;; The AsdfMetaobjectsE2eTest fixture: a plain defsystem with a :module (the
;; flattened-children shape), a tests system chained through :in-order-to, and
;; a :perform (test-op ...) body -- fukamachi's .asd shape, reduced.
(defsystem "meta-demo"
  :pathname "src"
  :components ((:file "one") (:module "m" :components ((:file "two"))))
  :in-order-to ((test-op (test-op "meta-demo/tests"))))

(defsystem "meta-demo/tests"
  :depends-on ("meta-demo")
  :components ((:file "tests"))
  :perform (test-op (o c) (run-demo-tests (component-name c))))
