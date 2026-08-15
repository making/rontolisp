;; The LoadContextE2eTest fixture: one component that records the load context
;; it was loaded under, and loads a companion of its own.
(defsystem "lc-demo" :pathname "src" :components ((:file "one")))
