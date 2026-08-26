;;; A replacement for cl-unicode.asd.
;;;
;;; Upstream declares four systems; two of them are unreachable from the
;;; defsystem-as-data front end, and the primary depends on the build one
;;; through machinery that does not exist here:
;;;
;;;   - cl-unicode/build declares :output-files (load-op ...) and performs
;;;     load-op by WRITING lists.lisp, hash-tables.lisp and methods.lisp
;;;     next to the sources -- the three components the primary system names
;;;     but the release does not ship.  A defmethod on component-depends-on
;;;     for prepare-op is what makes ASDF run it first.
;;;   - cl-unicode/test needs the generated test data and fiveam.
;;;
;;; rontolisp generates those three components itself, from the same bundled
;;; UCD data, at load time (eval/ClUnicodeTables) -- so the build system has
;;; nothing left to do and the dependency on it is gone.  Everything else is
;;; the real upstream source, loaded verbatim.

(defsystem :cl-unicode/base
  :depends-on (:cl-ppcre)
  :serial t
  :license "BSD-2-Clause"
  :components ((:file "packages") (:file "specials") (:file "util")))

(defsystem :cl-unicode
  :version "0.1.6"
  :serial t
  :description "Portable Unicode Library"
  :depends-on (:cl-unicode/base)
  :license "BSD-2-Clause"
  :components ((:file "conditions") (:file "lists") (:file "hash-tables")
               (:file "api") (:file "methods") (:file "test-functions")
               (:file "derived") (:file "alias")))
