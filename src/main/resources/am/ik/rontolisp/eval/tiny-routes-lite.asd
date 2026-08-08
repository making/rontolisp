;;;; Hand-authored replacement for tiny-routes.asd that keeps the verbatim
;;;; primary system and ADDS the opt-in "tiny-routes/lite" secondary system.
;;;;
;;;; Upstream's file parses fine as data; it is replaced only so the lite
;;;; system can exist WITHOUT touching what plain (ql:quickload "tiny-routes")
;;;; loads. "tiny-routes" below is upstream's own declaration (its duplicated
;;;; :depends-on option deduplicated -- real ASDF plist-reads the first
;;;; occurrence, (:cl-ppcre :uiop), and that is what is declared here), and
;;;; every component file of BOTH systems resolves against the real library
;;;; tree, because AsdOverrides keeps the located path.
;;;;
;;;; "tiny-routes/lite" is the same library with ONE component substituted and
;;;; ONE dependency dropped: src/middleware/path-template.lisp is replaced by
;;;; the ppcre-free matcher (ShimLibraries.leafModuleForms, keyed by THIS
;;;; system name -- the full system keeps the real file), so :cl-ppcre is not
;;;; depended on and the whole regex engine stays out of a compiled module.
;;;; Both tiers are needed together: the file substitution alone leaves the
;;;; loaded-but-unreferenced engine anchored through its CLOS surface
;;;; (measured -0.9%; .kb/optimize-dead-code-elimination.md). The matcher
;;;; accepts exactly the :name-token template subset and SIGNALS at route-build
;;;; time outside it, so a template never matches differently from the full
;;;; system -- see tiny-routes-lite-path-template.lisp. Loading both systems
;;;; in one program is refused (ShimLibraries.conflictingSystem).

(defsystem "tiny-routes"
  :description "A tiny routing library for Common Lisp targeting Clack."
  :author "Johnny Ruiz <johnny@ruiz-usa.com>"
  :version "0.1.1"
  :license "BSD 3-Clause"
  :serial t
  :depends-on (:cl-ppcre :uiop)
  :pathname "src/"
  :components ((:file "util")
               (:file "request")
               (:file "response")
               (:module "middleware"
                :serial t
                :components ((:file "builder")
                             (:file "method")
                             (:file "path-template")
                             (:file "query-parameters")
                             (:file "request-body")
                             (:file "response")
                             (:file "middleware")))
               (:file "tiny-routes"))
  :in-order-to ((test-op (test-op :tiny-routes/test))))

(defsystem "tiny-routes/lite"
  :description "tiny-routes with a ppcre-free path-template matcher (no :cl-ppcre)"
  :license "BSD 3-Clause"
  :serial t
  :depends-on (:uiop)
  :pathname "src/"
  :components ((:file "util")
               (:file "request")
               (:file "response")
               (:module "middleware"
                :serial t
                :components ((:file "builder")
                             (:file "method")
                             (:file "path-template")
                             (:file "query-parameters")
                             (:file "request-body")
                             (:file "response")
                             (:file "middleware")))
               (:file "tiny-routes")))

(defsystem "tiny-routes/test"
  :description "A tiny-routes test suite."
  :author "Johnny Ruiz <johnny@ruiz-usa.com>"
  :license "BSD 3-Clause"
  :depends-on (:tiny-routes :fiveam)
  :pathname "t/"
  :components ((:file "tiny-routes-test"))
  :perform (test-op (o c) (symbol-call :tiny-routes-test :run-tests)))
