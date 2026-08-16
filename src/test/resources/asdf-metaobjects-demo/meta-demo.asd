;; The AsdfMetaobjectsE2eTest fixture: a plain defsystem with a :module (the
;; flattened-children shape), a tests system chained through :in-order-to, and
;; a :perform (test-op ...) body -- fukamachi's .asd shape, reduced. It also
;; carries dexador.asd's two openers: a :version (read back by
;; asdf:component-version) and a :defsystem-depends-on on the built-in
;; trivial-features shim, whose announced features have to be in force while
;; src/one.lisp is READ.
(defsystem "meta-demo"
  :version "1.2.3"
  :defsystem-depends-on ("trivial-features")
  :pathname "src"
  :components ((:file "one") (:module "m" :components ((:file "two"))))
  :in-order-to ((test-op (test-op "meta-demo/tests"))))

(defsystem "meta-demo/tests"
  :depends-on ("meta-demo")
  :components ((:file "tests"))
  :perform (test-op (o c) (run-demo-tests (component-name c))))
