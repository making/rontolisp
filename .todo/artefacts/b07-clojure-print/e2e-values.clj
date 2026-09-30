;; Values built by the REAL Clojure lowering (not hand-built wrappers), for the
;; e2e driver to print through the spike printer.

(def e2e-false false)
(def e2e-true true)
(def e2e-nil nil)
(def e2e-kw :a)
(def e2e-KW :A)
(def e2e-vec [1 :a "s"])
(def e2e-map {:a 1})
(def e2e-nested {:a {:b [1 2]}})
(def e2e-set #{1})
(def e2e-list (list 1 :k true false nil))
(def e2e-sym 'e2e-foo)
