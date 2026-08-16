;; The mgl-pax package stub: satisfies the built-in ASDF system
;; "mgl-pax-bootstrap". Upstream mgl-pax is a full documentation system;
;; mgl-pax-bootstrap is its package-definition core, which trivial-utf-8 (a
;; uuid dependency, hence on the mito path) hard-depends on -- and whose own
;; .asd declares :around-compile, a compile hook outside the defsystem-as-data
;; subset (.kb/asdf.md). The swank precedent: without the stub, (ql:quickload "uuid")
;; dies parsing mgl-pax's system definition.
;;
;; Written in canonical shape; the mgl-pax package (nickname pax) is seeded in
;; PackageRegistry. Only what trivial-utf-8's source actually calls is defined:
;; - pax:define-package is consumed by PackageResolver.resolve as defpackage
;;   (no definition here -- it never survives to evaluation).
;; - pax:defsection defines the section name as a nil variable, which is what
;;   makes a later (defun pax-sections () (list @manual)) reference compile;
;;   the body (titles, entry lists, docstrings) is documentation data and is
;;   dropped.
;; - The PAX-World registration pair are nil no-ops: registering documentation
;;   for a renderer that does not exist here has no honest effect. Unlike
;;   swank:create-server, a no-op is right -- nothing the caller asked for is
;;   silently lost, since no PAX-World renderer can ever run.

(defmacro mgl-pax:defsection (name &rest args)
  (declare (ignore args))
  `(defvar ,name nil))

(defun mgl-pax:make-github-source-uri-fn (&rest args)
  (declare (ignore args))
  nil)

(defun mgl-pax:register-doc-in-pax-world (&rest args)
  (declare (ignore args))
  nil)
