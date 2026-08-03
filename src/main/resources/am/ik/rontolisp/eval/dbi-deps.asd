;;;; Hand-authored replacement for cl-dbi's dbi.asd.
;;;;
;;;; The upstream .asd PARSES fine (its #1= labels, (:feature ...) dependency
;;;; and :if-feature components all read as data); what this replacement
;;;; changes is ONE decision upstream takes from a thread-capability feature
;;;; expression -- (:or :abcl (:and :sbcl :sb-thread) ...) -- that can never
;;;; match rontolisp's feature set, and that MUST NOT be satisfied by claiming
;;;; a backend feature we do not have (the :rontolisp-features declaration is
;;;; additive precisely so a system cannot pretend to be another backend).
;;;;
;;;; The decision (the postmodern-deps :postmodern-thread-safe precedent):
;;;; dbi's cache pool is the per-thread connection cache behind connect-cached,
;;;; and rontolisp DOES run concurrent handlers (one virtual thread per served
;;;; request; lack-middleware-mito calls connect-cached from exactly that
;;;; context). So on the thread-capable backends (interpreter, JVM) this file
;;;; selects cache/thread.lisp -- per-thread cache tables keyed by
;;;; bt2:current-thread under a real bt2 lock -- and declares the
;;;; bordeaux-threads dependency that upstream gates behind the same feature
;;;; expression. On the single-threaded WASM backends it selects
;;;; cache/single.lisp, which is upstream's own choice for a threadless
;;;; implementation (there the bt shim's locks are no-op tautologies anyway).
;;;;
;;;; Component paths resolve against the located dbi.asd's directory, so the
;;;; REAL cl-dbi sources are loaded. The dbi/test system is not reproduced
;;;; (rove, dbd-mysql and friends are out of scope).

(defsystem "dbi"
  :description "Database independent interface for Common Lisp"
  :depends-on ("split-sequence"
               "closer-mop"
               "cl-ppcre"
               "bordeaux-threads")
  :components ((:module "src"
                :depends-on ("src/utils")
                :components
                ((:file "dbi" :depends-on ("driver" "cache" "logger"))
                 (:file "driver" :depends-on ("error"))
                 (:module "cache"
                  :components
                  ((:file "thread" :if-feature (:not :rontolisp-wasm))
                   (:file "single" :if-feature :rontolisp-wasm)))
                 (:file "logger")
                 (:file "error")))
               (:file "src/utils")))
