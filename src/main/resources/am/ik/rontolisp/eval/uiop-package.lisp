;;;; uiop/package -- the symbol and package surgery family.
;;;;
;;;; Canonical shape; see .kb/uiop.md.
;;;;
;;;; A rontolisp symbol is a STRING, not an interned object, and
;;;; *package* folds at resolution time (.kb/packages.md), so this family splits
;;;; in two. The name-level questions the registry can serve are real here, over
;;;; the CL package operators every backend already carries; the surgery that
;;;; MOVES a symbol between packages while every reference follows it needs
;;;; symbol identity, which does not exist -- those carry real definitions that
;;;; signal uiop:not-implemented-error naming the operation and saying so
;;;; ("rontolisp has no image to upgrade": upstream needs them to hot-upgrade
;;;; ASDF inside a running image, and there is no image here). The per-name
;;;; verdicts live in .kb/uiop.md.

(defun uiop/package:find-package* (%fp-designator &optional (%fp-error t))
  (let ((%fp-package (find-package %fp-designator)))
    (cond (%fp-package %fp-package)
          (%fp-error
           (error 'uiop/package*:no-such-package-error :datum %fp-designator))
          (t nil))))

;; Two values, as CL's find-symbol: the symbol and its status. The status the
;; compiled backends report is the one .kb/symbol-runtime-api.md describes -- a
;; compiled find-symbol BUILDS the qualified spelling, so a name the package does
;; not own still answers a symbol where the interpreter answers nil,
;; and with :error true that difference is the difference between returning and
;; signalling.
(defun uiop/package:find-symbol*
    (%fs-name %fs-designator &optional (%fs-error t))
  (let ((%fs-package (uiop/package:find-package* %fs-designator %fs-error)))
    (if (null %fs-package)
        (values nil nil)
        (multiple-value-bind (%fs-symbol %fs-status)
            (find-symbol (string %fs-name) %fs-package)
          (cond (%fs-status (values %fs-symbol %fs-status))
                (%fs-error (error "There is no symbol ~S in package ~S" %fs-name
                                  (package-name %fs-package)))
                (t (values nil nil)))))))

;; Upstream's own shape, and here for the same reason file-exists-p carries one:
;; symbol-call in CALL position is folded away (expandUiopStubCall lowers it to a
;; runtime intern + funcall), so this definition is what a FIRST-CLASS
;; #'uiop:symbol-call resolves to -- the (apply #'uiop:symbol-call '#:pkg '#:name
;; args) backend dispatch dexador writes. The interpreter keeps its Java built-in,
;; which resolves first and never lets this one load.
(defun uiop/package:symbol-call (%sc-package %sc-name &rest %sc-args)
  (apply (uiop/package:find-symbol* %sc-name %sc-package) %sc-args))

;; Upstream interns into the CURRENT package when the designator names nothing
;; and error is nil ((intern name nil)); a two-argument intern has no
;; current-package default here (a nil computed designator signals), so a
;; missing package with no error answers nil instead -- the same shape as
;; find-symbol* above it.
(defun uiop/package:intern* (%in-name %in-designator &optional (%in-error t))
  (let ((%in-package (uiop/package:find-package* %in-designator %in-error)))
    (if (null %in-package) nil (intern (string %in-name) %in-package))))

;; Real where the registry can take a mutation at all: the interpreter runs the
;; CL operator (a literal top-level call folds at resolution time, a computed
;; one mutates the live registry), and the compiled backends answer exactly
;; what CL's own export/import answer there -- the arguments evaluated for
;; effect plus t, the registry being frozen (LispMacroExpander).
(defun uiop/package:export* (%ex-name %ex-designator)
  (let* ((%ex-package (uiop/package:find-package* %ex-designator))
         (%ex-symbol (uiop/package:intern* %ex-name %ex-package)))
    (export %ex-symbol %ex-package)))

(defun uiop/package:import* (%im-symbol %im-designator)
  (import %im-symbol (uiop/package:find-package* %im-designator)))

(defun uiop/package:make-symbol* (%ms-name)
  (etypecase %ms-name
    (string (make-symbol %ms-name))
    (symbol (copy-symbol %ms-name))))

;; Always nil, and honestly so: package-shadowing-symbols answers nil on every
;; backend (runtime shadow/shadowing-import are documented non-goals,
;; .kb/packages.md), so nothing shadows anything.
(defun uiop/package:symbol-shadowing-p (%ssp-symbol %ssp-package)
  (and (member %ssp-symbol (package-shadowing-symbols %ssp-package)) t))

(defun uiop/package:home-package-p (%hpp-symbol %hpp-package)
  (and %hpp-package
       (let ((%hpp-sp (symbol-package %hpp-symbol)))
         (and %hpp-sp
              (let ((%hpp-pp (uiop/package:find-package* %hpp-package)))
                (and %hpp-pp (eq %hpp-sp %hpp-pp)))))))

(defun uiop/package:symbol-package-name (%spn-symbol)
  (let ((%spn-package (symbol-package %spn-symbol)))
    (and %spn-package (package-name %spn-package))))

;; Upstream reads (find-symbol* symbol :common-lisp nil) and tests the status;
;; a symbol IS its spelling here, so the same question is whether its home is
;; cl -- symbol-package answers it from the table of the standard names on the
;; compiled backends, and a string, keyword or uninterned symbol answers nil.
(defun uiop/package:standard-common-lisp-symbol-p (%sclsp-symbol)
  (and (symbolp %sclsp-symbol) (eq (symbol-package %sclsp-symbol) :cl)))

(defun uiop/package:package-names (%pn-package)
  (let ((%pn-found (uiop/package:find-package* %pn-package)))
    (cons (package-name %pn-found) (package-nicknames %pn-found))))

(defun uiop/package:packages-from-names (%pfn-names)
  (let ((%pfn-found nil))
    (dolist (%pfn-name %pfn-names
                       (remove-duplicates (nreverse %pfn-found) :from-end t))
      (let ((%pfn-package (find-package %pfn-name)))
        (when %pfn-package (push %pfn-package %pfn-found))))))

(defun uiop/package:fresh-package-name (&key
                                        ((:prefix %fpn-prefix) :%to-be-deleted)
                                        ((:separator %fpn-separator) nil)
                                        ((:index %fpn-index)
                                         (random most-positive-fixnum)))
  (labels ((%fpn-try (%fpn-i)
             (let ((%fpn-n
                    (if (plusp %fpn-i)
                        (concatenate 'string (string %fpn-prefix)
                         (if (null %fpn-separator) "" (string %fpn-separator))
                         (princ-to-string %fpn-i))
                        (string %fpn-prefix))))
               (if (null (find-package %fpn-n))
                   %fpn-n
                   (%fpn-try (1+ %fpn-i))))))
    (%fpn-try %fpn-index)))

;; A fresh name plus rename-package, nothing else: it works wherever
;; rename-package works (a runtime-tier package), and upstream's fishiness log
;; has no surface here.
(defun uiop/package:rename-package-away
    (%rpa-p &rest %rpa-keys &key ((:prefix %rpa-prefix) nil) &allow-other-keys)
  (let ((%rpa-new-name
         (apply #'uiop/package:fresh-package-name
                :prefix (or %rpa-prefix
                         (concatenate 'string "__" (package-name %rpa-p) "__"))
                %rpa-keys)))
    (rename-package %rpa-p %rpa-new-name)))

;; The defining form of a package, from the enumeration every backend agrees
;; on: do-symbols yields each member under its owner's spelling
;; (.kb/packages.md), so the qualifier decides the bucket -- owned external
;; and internal spellings land in :export and :intern, a name owned elsewhere
;; lands in an :import-from under its true home, and whatever the use list
;; already provides is skipped (the :use clause names those packages). Two
;; lites, both from having no intern table: a defun-defined name is not in the
;; registry's universe, so the form covers the declared members (what
;; defpackage said, plus imports), and an inherited member of a package the
;; program merely uses is skipped even when it was genuinely imported -- the
;; :use clause still provides it, so the form re-evaluates to the same
;; symbols.
(defun uiop/package:package-definition-form (%pdf-designator &key
                                             ((:nicknamesp %pdf-nicknamesp) t)
                                             ((:usep %pdf-usep) t)
                                             ((:shadowp %pdf-shadowp) t)
                                             ((:shadowing-import-p
                                               %pdf-shadowing-import-p) t)
                                             ((:exportp %pdf-exportp) t)
                                             ((:importp %pdf-importp) t)
                                             ((:internp %pdf-internp) nil)
                                             ((:error %pdf-error) t))
  (let ((%pdf-package (uiop/package:find-package* %pdf-designator %pdf-error)))
    (if (null %pdf-package)
        nil
        (labels ((%pdf-sort-names (%pdf-names)
                   (sort (copy-list %pdf-names)
                         (lambda (%pdf-a %pdf-b) (string< %pdf-a %pdf-b))))
                 (%pdf-home-names-p (%pdf-h %pdf-name %pdf-nicks)
                   (or (string= %pdf-h %pdf-name)
                       (let ((%pdf-hit nil))
                         (dolist (%pdf-nick %pdf-nicks %pdf-hit)
                           (when (string= %pdf-h %pdf-nick)
                             (setq %pdf-hit t))))))
                 (%pdf-inherited-p (%pdf-n %pdf-inherited)
                   (let ((%pdf-hit nil))
                     (dolist (%pdf-i %pdf-inherited %pdf-hit)
                       (when (string= %pdf-n %pdf-i) (setq %pdf-hit t)))))
                 (%pdf-record-import (%pdf-h %pdf-n %pdf-import)
                   (let ((%pdf-cell nil))
                     (dolist (%pdf-e %pdf-import)
                       (when (string= %pdf-h (car %pdf-e))
                         (setq %pdf-cell %pdf-e)))
                     (if %pdf-cell
                         (push %pdf-n (cdr %pdf-cell))
                         (push (cons %pdf-h (list %pdf-n)) %pdf-import)))
                   %pdf-import))
          (let ((%pdf-name (package-name %pdf-package))
                (%pdf-nicknames (package-nicknames %pdf-package))
                (%pdf-use
                 (let ((%pdf-u nil))
                   (dolist (%pdf-up (package-use-list %pdf-package)
                                    (nreverse %pdf-u))
                     (push (package-name %pdf-up) %pdf-u))))
                (%pdf-inherited
                 (let ((%pdf-i nil))
                   (dolist (%pdf-up (package-use-list %pdf-package) %pdf-i)
                     (do-external-symbols (%pdf-s %pdf-up)
                       (push (symbol-name %pdf-s) %pdf-i)))))
                (%pdf-shadow nil)
                (%pdf-import nil)
                (%pdf-export nil)
                (%pdf-intern nil))
            (do-symbols (%pdf-sym %pdf-package)
              (let* ((%pdf-text (prin1-to-string %pdf-sym))
                     (%pdf-idx (position #\: %pdf-text)))
                (cond ((null %pdf-idx)
                       ;; Bare: a cl/cl-user member, or an owned name of a
                       ;; package whose members read bare. Inherited ones ride
                       ;; the :use clause; the rest are interned -- except in
                       ;; cl itself, where everything bare is exported.
                       (when (not (%pdf-inherited-p %pdf-text %pdf-inherited))
                         (if (string= %pdf-name "CL")
                             (push %pdf-text %pdf-export)
                             (push %pdf-text %pdf-intern))))
                      ((and (< (1+ %pdf-idx) (length %pdf-text))
                            (char= (char %pdf-text (1+ %pdf-idx)) #\:))
                       (let ((%pdf-h (subseq %pdf-text 0 %pdf-idx))
                             (%pdf-n (subseq %pdf-text (+ %pdf-idx 2))))
                         (when (%pdf-home-names-p %pdf-h %pdf-name
                                                  %pdf-nicknames)
                           (push %pdf-n %pdf-intern))))
                      (t (let ((%pdf-h (subseq %pdf-text 0 %pdf-idx))
                               (%pdf-n (subseq %pdf-text (1+ %pdf-idx))))
                           (cond ((%pdf-home-names-p %pdf-h %pdf-name
                                                     %pdf-nicknames)
                                  (push %pdf-n %pdf-export))
                                 ;; A member of a used package rides the :use
                                 ;; clause, whether it was genuinely imported or
                                 ;; merely inherited -- the form re-evaluates to
                                 ;; the same symbols either way.
                                 ((%pdf-inherited-p %pdf-n %pdf-inherited))
                                 (t (setq %pdf-import
                                          (%pdf-record-import %pdf-h %pdf-n
                                           %pdf-import)))))))))
            (let ((%pdf-sorted-export (%pdf-sort-names %pdf-export))
                  (%pdf-sorted-intern (%pdf-sort-names %pdf-intern))
                  (%pdf-sorted-nicks (%pdf-sort-names %pdf-nicknames))
                  (%pdf-sorted-use (%pdf-sort-names %pdf-use))
                  (%pdf-sorted-import-keys
                   (%pdf-sort-names
                    (let ((%pdf-k nil))
                      (dolist (%pdf-e %pdf-import %pdf-k)
                        (push (car %pdf-e) %pdf-k))))))
              `(defpackage ,%pdf-name
                 ,@(if (and %pdf-nicknamesp %pdf-sorted-nicks)
                       (list (cons :nicknames %pdf-sorted-nicks))
                       nil)
                 (:use ,@(if %pdf-usep %pdf-sorted-use nil))
                 ,@(if (and %pdf-shadowp %pdf-shadow)
                       (list (cons :shadow (%pdf-sort-names %pdf-shadow)))
                       nil)
                 ,@(if %pdf-shadowing-import-p
                       (mapcar (lambda (%pdf-k)
                                 (cons :shadowing-import-from
                                       (cons %pdf-k
                                             (%pdf-sort-names
                                              (cdr
                                               (assoc %pdf-k %pdf-import
                                                      :test #'string=))))))
                               %pdf-sorted-import-keys)
                       nil)
                 ,@(if %pdf-importp
                       (mapcar (lambda (%pdf-k)
                                 (cons :import-from
                                       (cons %pdf-k
                                             (%pdf-sort-names
                                              (cdr
                                               (assoc %pdf-k %pdf-import
                                                      :test #'string=))))))
                               %pdf-sorted-import-keys)
                       nil)
                 ,@(if (and %pdf-exportp %pdf-sorted-export)
                       (list (cons :export %pdf-sorted-export))
                       nil)
                 ,@(if (and %pdf-internp %pdf-sorted-intern)
                       (list (cons :intern %pdf-sorted-intern))
                       nil))))))))

;; Pure list surgery over the clause structure, exactly upstream's: each clause
;; is (KEYWORD . ARGS), :use-reexport/:mix-reexport fan out into :use/:mix plus
;; :reexport, and a missing :use defaults to (:common-lisp) while a missing
;; :recycle defaults to the package itself. One divergence: the
;; :local-nicknames clause is always accepted -- rontolisp always carries the
;; lite-global nickname machinery, so the feature test upstream gates it on
;; could only ever refuse.
(defun uiop/package:parse-define-package-form (%pdp-package %pdp-clauses)
  (let ((%pdp-nicknames nil)
        (%pdp-documentation nil)
        (%pdp-use nil)
        (%pdp-use-p nil)
        (%pdp-shadow nil)
        (%pdp-shadowing-import-from nil)
        (%pdp-import-from nil)
        (%pdp-export nil)
        (%pdp-intern nil)
        (%pdp-recycle nil)
        (%pdp-recycle-p nil)
        (%pdp-mix nil)
        (%pdp-reexport nil)
        (%pdp-unintern nil)
        (%pdp-local-nicknames nil))
    (dolist (%pdp-clause %pdp-clauses)
      (let ((%pdp-kw (car %pdp-clause)) (%pdp-args (cdr %pdp-clause)))
        (cond ((eq %pdp-kw :nicknames)
               (setq %pdp-nicknames (append %pdp-nicknames %pdp-args)))
              ((eq %pdp-kw :documentation)
               (cond (%pdp-documentation
                      (error
                       "define-package: can't define documentation twice"))
                     ((or (atom %pdp-args) (cdr %pdp-args))
                      (error "define-package: bad documentation"))
                     (t (setq %pdp-documentation (car %pdp-args)))))
              ((eq %pdp-kw :use)
               (setq %pdp-use (append %pdp-use %pdp-args))
               (setq %pdp-use-p t))
              ((eq %pdp-kw :shadow)
               (setq %pdp-shadow (append %pdp-shadow %pdp-args)))
              ((eq %pdp-kw :shadowing-import-from)
               (push %pdp-args %pdp-shadowing-import-from))
              ((eq %pdp-kw :import-from) (push %pdp-args %pdp-import-from))
              ((eq %pdp-kw :export)
               (setq %pdp-export (append %pdp-export %pdp-args)))
              ((eq %pdp-kw :intern)
               (setq %pdp-intern (append %pdp-intern %pdp-args)))
              ((eq %pdp-kw :recycle)
               (setq %pdp-recycle (append %pdp-recycle %pdp-args))
               (setq %pdp-recycle-p t))
              ((eq %pdp-kw :mix) (setq %pdp-mix (append %pdp-mix %pdp-args)))
              ((eq %pdp-kw :reexport)
               (setq %pdp-reexport (append %pdp-reexport %pdp-args)))
              ((eq %pdp-kw :use-reexport)
               (setq %pdp-use (append %pdp-use %pdp-args))
               (setq %pdp-reexport (append %pdp-reexport %pdp-args))
               (setq %pdp-use-p t))
              ((eq %pdp-kw :mix-reexport)
               (setq %pdp-mix (append %pdp-mix %pdp-args))
               (setq %pdp-reexport (append %pdp-reexport %pdp-args))
               (setq %pdp-use-p t))
              ((eq %pdp-kw :unintern)
               (setq %pdp-unintern (append %pdp-unintern %pdp-args)))
              ((eq %pdp-kw :local-nicknames)
               (setq %pdp-local-nicknames
                     (append %pdp-local-nicknames %pdp-args)))
              (t (error "unrecognized define-package keyword ~S" %pdp-kw)))))
    `(',%pdp-package
      :nicknames ',%pdp-nicknames
      :documentation ',%pdp-documentation
      :use ',(if %pdp-use-p %pdp-use '(:common-lisp))
      :shadow ',%pdp-shadow
      :shadowing-import-from ',(nreverse %pdp-shadowing-import-from)
      :import-from ',(nreverse %pdp-import-from)
      :export ',%pdp-export
      :intern ',%pdp-intern
      :recycle ',(if %pdp-recycle-p
                     %pdp-recycle
                     (cons %pdp-package %pdp-nicknames))
      :mix ',%pdp-mix
      :reexport ',%pdp-reexport
      :unintern ',%pdp-unintern
      ,@(if %pdp-local-nicknames
            (list (list :local-nicknames (list 'quote %pdp-local-nicknames)))
            nil))))

;; The package-local nickname query. Nicknames are global here (no per-package
;; scoping, .kb/packages.md), so this answers every global nickname pointing
;; at the package, as (NICKNAME-STRING . PACKAGE-KEYWORD) pairs -- the shape
;; upstream's per-package table has, over the scope that exists. The removal
;; half is remove-package-local-nickname: a literal top-level call is consumed
;; at resolve time like the add, and anything else is an interpreter runtime
;; (it mutates the registry, like the computed add-package-local-nickname
;; call).
(defun uiop/package-local-nicknames:package-local-nicknames (%pln-package)
  (let ((%pln-found (uiop/package:find-package* %pln-package)) (%pln-acc nil))
    (dolist (%pln-nick (package-nicknames %pln-found) (nreverse %pln-acc))
      (push (cons %pln-nick %pln-found) %pln-acc))))

;; The reify/unreify pair, the nuke pair, the rehome mover and the rest of the
;; load-time surgery -- delete-package*, ensure-package-unused,
;; ensure-package, unintern*, shadow* and shadowing-import* -- need symbol
;; identity: their whole point is to move a symbol between packages while
;; every existing reference follows, and a string cannot follow anything.
;; Upstream needs them to hot-upgrade ASDF inside a running image; rontolisp
;; has no image to upgrade, so each names its operation and says so, rather
;; than pretending. (export* and import* above are the deliberate exceptions:
;; the registry takes those two mutations wherever the language lets it.)
(defun uiop/package:rehome-symbol (%rhs-symbol %rhs-designator)
  (declare (ignore %rhs-symbol %rhs-designator))
  (uiop/utility:not-implemented-error "UIOP/PACKAGE:REHOME-SYMBOL"
                                      "moving a symbol between packages needs symbol identity, and rontolisp has no image to upgrade"))

(defun uiop/package:nuke-symbol-in-package (%nsp-symbol %nsp-designator)
  (declare (ignore %nsp-symbol %nsp-designator))
  (uiop/utility:not-implemented-error "UIOP/PACKAGE:NUKE-SYMBOL-IN-PACKAGE"
                                      "moving a symbol between packages needs symbol identity, and rontolisp has no image to upgrade"))

(defun uiop/package:nuke-symbol
    (%ns-symbol &optional (%ns-packages (list-all-packages)))
  (declare (ignore %ns-symbol %ns-packages))
  (uiop/utility:not-implemented-error "UIOP/PACKAGE:NUKE-SYMBOL"
                                      "moving a symbol between packages needs symbol identity, and rontolisp has no image to upgrade"))

(defun uiop/package:reify-package (%rp-package &optional (%rp-context nil))
  (declare (ignore %rp-package %rp-context))
  (uiop/utility:not-implemented-error "UIOP/PACKAGE:REIFY-PACKAGE"
                                      "the reify/unreify pair serializes symbols for the package upgrade surgery, and rontolisp has no image to upgrade"))

(defun uiop/package:unreify-package (%urp-package &optional (%urp-context nil))
  (declare (ignore %urp-package %urp-context))
  (uiop/utility:not-implemented-error "UIOP/PACKAGE:UNREIFY-PACKAGE"
                                      "the reify/unreify pair serializes symbols for the package upgrade surgery, and rontolisp has no image to upgrade"))

(defun uiop/package:reify-symbol (%rs-symbol &optional (%rs-context nil))
  (declare (ignore %rs-symbol %rs-context))
  (uiop/utility:not-implemented-error "UIOP/PACKAGE:REIFY-SYMBOL"
                                      "the reify/unreify pair serializes symbols for the package upgrade surgery, and rontolisp has no image to upgrade"))

(defun uiop/package:unreify-symbol (%urs-symbol &optional (%urs-context nil))
  (declare (ignore %urs-symbol %urs-context))
  (uiop/utility:not-implemented-error "UIOP/PACKAGE:UNREIFY-SYMBOL"
                                      "the reify/unreify pair serializes symbols for the package upgrade surgery, and rontolisp has no image to upgrade"))

(defun uiop/package:unintern* (%ui-name %ui-designator &optional (%ui-error t))
  (declare (ignore %ui-name %ui-designator %ui-error))
  (uiop/utility:not-implemented-error "UIOP/PACKAGE:UNINTERN*"
                                      "uninterning needs an intern table to remove the symbol from, and rontolisp has no image to upgrade"))

(defun uiop/package:shadow* (%sh-name %sh-designator)
  (declare (ignore %sh-name %sh-designator))
  (uiop/utility:not-implemented-error "UIOP/PACKAGE:SHADOW*"
                                      "runtime shadowing needs symbol identity to shadow with, and rontolisp has no image to upgrade"))

(defun uiop/package:shadowing-import* (%si-symbol %si-designator)
  (declare (ignore %si-symbol %si-designator))
  (uiop/utility:not-implemented-error "UIOP/PACKAGE:SHADOWING-IMPORT*"
                                      "runtime shadowing needs symbol identity to shadow with, and rontolisp has no image to upgrade"))

(defun uiop/package:ensure-package-unused (%epu-package)
  (declare (ignore %epu-package))
  (uiop/utility:not-implemented-error "UIOP/PACKAGE:ENSURE-PACKAGE-UNUSED"
                                      "ununusing a package before deleting it is upgrade hygiene, and rontolisp has no image to upgrade"))

(defun uiop/package:delete-package* (%dp-package &key ((:nuke %dp-nuke) nil))
  (declare (ignore %dp-package %dp-nuke))
  (uiop/utility:not-implemented-error "UIOP/PACKAGE:DELETE-PACKAGE*"
                                      "deleting a package out from under a running image is upgrade surgery, and rontolisp has no image to upgrade"))

(defun uiop/package:ensure-package (%ep-name &key
                                    ((:nicknames %ep-nicknames) nil)
                                    ((:documentation %ep-documentation) nil)
                                    ((:use %ep-use) nil)
                                    ((:shadow %ep-shadow) nil)
                                    ((:shadowing-import-from
                                      %ep-shadowing-import-from) nil)
                                    ((:import-from %ep-import-from) nil)
                                    ((:export %ep-export) nil)
                                    ((:intern %ep-intern) nil)
                                    ((:recycle %ep-recycle) nil)
                                    ((:mix %ep-mix) nil)
                                    ((:reexport %ep-reexport) nil)
                                    ((:unintern %ep-unintern) nil)
                                    ((:local-nicknames %ep-local-nicknames)
                                     nil))
  (declare
   (ignore %ep-name %ep-nicknames %ep-documentation %ep-use %ep-shadow
           %ep-shadowing-import-from %ep-import-from %ep-export %ep-intern
           %ep-recycle %ep-mix %ep-reexport %ep-unintern %ep-local-nicknames))
  (uiop/utility:not-implemented-error "UIOP/PACKAGE:ENSURE-PACKAGE"
                                      "redefining a package at run time is upgrade surgery needing symbol identity, and rontolisp has no image to upgrade -- define the package with uiop:define-package or defpackage instead"))

;; The uiop/package* trio: the condition type-error carries, the style-warning
;; a redefinition would signal, and the designator predicate plus its reader.
;; Upstream's package-designator is a method on no-such-package-error reading
;; the type-error datum; here it is that reader as a plain function.
(deftype uiop/package*:package-designator ()
  '(and (or package character string symbol) (satisfies find-package)))

(define-condition uiop/package*:no-such-package-error (type-error)
  ()
  (:report
   (lambda (%nspe-c %nspe-s)
     (write-string "No package named " %nspe-s)
     (write-string (string (type-error-datum %nspe-c)) %nspe-s))))

(defun uiop/package*:package-designator (%pd-c) (type-error-datum %pd-c))

(define-condition uiop/package*:define-package-style-warning (style-warning) ())
