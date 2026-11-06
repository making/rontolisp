;; The LoadContextE2eTest fixture: a component that records the load context
;; it was loaded under, and loads a companion of its own, and a second :serial
;; component read with the feature the first one pushed.
(defsystem "lc-demo" :pathname "src" :serial t :components ((:file "one") (:file "two")))
