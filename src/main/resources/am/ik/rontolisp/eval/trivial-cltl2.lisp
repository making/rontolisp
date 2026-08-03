;; The trivial-cltl2 package: a shim satisfying the built-in ASDF system
;; "trivial-cltl2" (a :depends-on of trivia.level2). The real library is a pure
;; re-export of each host implementation's CLtL2 environment API (sb-cltl2,
;; ccl, hcl, ...); on rontolisp every implementation branch of its one source
;; file is feature-false, so loading it verbatim yields a package whose every
;; export is undefined. Only the two members trivia level2 calls are defined
;; here; the remaining exports (compiler-let, variable-information,
;; function-information, augment-environment, parse-macro, enclose) resolve
;; through the seeded package but are undefined-function errors when called
;; (the uiop stub convention).
;; Written in canonical (pre-resolved) shape like closer-mop.lisp; the package
;; and its nickname (cltl2) are seeded in PackageRegistry.

;; Declarations are no-ops on every backend, so there is no declaration
;; registry to extend: registering nothing is the whole truth of this macro.
;; The handler body could never be consulted anyway, because
;; declaration-information below never has information to hand back. Expands
;; to the declaration name like the real one returns it.
(defmacro trivial-cltl2:define-declaration (decl-name lambda-list &body body)
  (declare (ignore lambda-list body))
  `(quote ,decl-name))

;; Always nil: declaim/proclaim are no-ops, so no declaration is ever in
;; effect. trivia level2's match2*+ falls back to its *optimizer* special on
;; the nil answer -- exactly the trivia.trivial (:trivial optimizer) route.
(defun trivial-cltl2:declaration-information (decl-name &optional env)
  (declare (ignore decl-name env))
  nil)
