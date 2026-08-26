;;;; A replacement for upstream cffi's own cffi.asd (eval.AsdOverrides).
;;;;
;;;; The real file cannot be read as data for two independent reasons: it opens
;;;; with (error "Sorry, this Lisp is not yet supported") for an implementation
;;;; it does not recognise -- rontolisp is not in its list and never will be,
;;;; the list is upstream's -- and it ends in a defmethod version-satisfies,
;;;; which is a program, not a system definition.
;;;;
;;;; What changes here, and nothing else:
;;;;
;;;; - the implementation component is (:file "cffi-rontolisp"), spliced from
;;;;   the bundled resource of that name (eval.ShimLibraries) rather than read
;;;;   from upstream's tree, which is therefore never edited;
;;;; - the dozen dead per-implementation components are dropped (their
;;;;   :if-feature never matches here);
;;;; - the trailing defmethod and the test-op clauses are dropped: test-op
;;;;   would pull cffi-tests, which grovels.
;;;;
;;;; :64-bit -- which types.lisp reads to give :size a base type -- is NOT
;;;; declared here: :defsystem-depends-on (:trivial-features) announces it, the
;;;; same route upstream takes, and eval.BuiltinSystems is where it is decided.

(in-package :asdf)

(defsystem "cffi"
  :description "The Common Foreign Function Interface"
  :author "James Bielman  <jamesjb@jamesjb.com>"
  :maintainer "Luis Oliveira  <loliveira@common-lisp.net>"
  :licence "MIT"
  :defsystem-depends-on (:trivial-features)
  :depends-on ((:feature :darwin :uiop) :alexandria :babel)
  :components ((:module "src"
                        :serial t
                        :components ((:file "package") (:file "sys-utils")
                                     (:file "cffi-rontolisp") (:file "utils")
                                     (:file "darwin-frameworks"
                                            :if-feature :darwin)
                                     (:file "libraries") (:file "early-types")
                                     (:file "types") (:file "enum")
                                     (:file "strings") (:file "structures")
                                     (:file "functions") (:file "foreign-vars")
                                     (:file "features")))))
