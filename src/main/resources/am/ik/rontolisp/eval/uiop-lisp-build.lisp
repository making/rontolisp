;;;; uiop/lisp-build -- compiling a file. Canonical shape; see .kb/uiop.md.
;;;;
;;;; rontolisp compiles a whole program -- no compile-file, no fasl, no
;;;; compilation-unit protocol -- so the portable half (the muffled-conditions
;;;; family, the condition classes, load*, reify-simple-sexp and the pathname
;;;; plumbing) is real and the compile-file*/deferred-warnings machinery
;;;; signals uiop:not-implemented-error naming the operation.

;; The pathname type a compiled file carries. rontolisp has no compile-file --
;; the compile backends compile a whole program, and a library file is spliced
;; into it -- so there is no compiled-file type, and nil says exactly that. Its
;; callers ask "is this path a fasl?" (rove's resolve-file), and against nil a
;; source path answers no.
(defun uiop/lisp-build:compile-file-type (&rest %cft-keys)
  (declare (ignore %cft-keys))
  nil)

;;; The variables. The two behaviour flags keep upstream's defaults (warn) so
;;; check-lisp-compile-warnings reacts to a build the same way it would on an
;;; implementation with no conditional feature; *base-build-directory* and
;;; *compile-check* are upstream's nil defaults (the hook that decides success
;;; is compile-file*'s, and that signals).
(defvar uiop/lisp-build:*base-build-directory* nil)
(defvar uiop/lisp-build:*compile-check* nil)
(defvar uiop/lisp-build:*compile-file-failure-behaviour* :warn)
(defvar uiop/lisp-build:*compile-file-warnings-behaviour* :warn)

;; The uninteresting-conditions variables. Upstream seeds them with
;; implementation-specific condition names (SBCL's compiler notes, LispWorks'
;; redefinition warnings); rontolisp has none of those to skip, so the honest
;; seed is empty -- a muffling call with nothing to match muffles nothing.
;; *usual-uninteresting-conditions* is upstream's suggested value for
;; *uninteresting-conditions*; kept as the empty seed a library can bind onto.
(defvar uiop/lisp-build:*usual-uninteresting-conditions* '())
(defvar uiop/lisp-build:*uninteresting-conditions* '())
(defvar uiop/lisp-build:*uninteresting-compiler-conditions* '())
(defvar uiop/lisp-build:*uninteresting-loader-conditions* '())

(defvar uiop/lisp-build:*warnings-file-type* nil)

;;; The condition classes. Real: a handler that names one of them must find the
;;; class. The report mirrors upstream -- the description, or the type name,
;;; with the context format appended when present -- without the justification
;;; directives upstream's format control leans on.
(define-condition uiop/lisp-build:compile-condition (condition)
  ((context-format :initform nil
                   :initarg :context-format
                   :reader uiop/lisp-build::%cc-context-format)
   (context-arguments :initform nil
                      :initarg :context-arguments
                      :reader uiop/lisp-build::%cc-context-arguments)
   (description :initform nil
                :initarg :description
                :reader uiop/lisp-build::%cc-description))
  (:report
   (lambda (%cc-c %cc-s)
     (write-string (or (uiop/lisp-build::%cc-description %cc-c)
                       (princ-to-string (type-of %cc-c))) %cc-s)
     (let ((%cc-fmt (uiop/lisp-build::%cc-context-format %cc-c)))
       (when %cc-fmt
         (write-string " while " %cc-s)
         (write-string (apply #'format nil %cc-fmt
                              (uiop/lisp-build::%cc-context-arguments %cc-c))
                       %cc-s))))))

(define-condition uiop/lisp-build:compile-file-error (compile-condition error)
  ())
(define-condition uiop/lisp-build:compile-warned-warning
    (compile-condition warning)
  ())
(define-condition uiop/lisp-build:compile-warned-error (compile-condition error)
  ())
(define-condition uiop/lisp-build:compile-failed-warning
    (compile-condition warning)
  ())
(define-condition uiop/lisp-build:compile-failed-error (compile-condition error)
  ())

;;; Raising on the behaviour flags. Portable over the two flags and the real
;;; condition classes; a caller feeds it the warnings/failure of a build it
;;; performed (here there is no compile-file, so nothing performs one, but the
;;; decision stays a real one).
(defun uiop/lisp-build:check-lisp-compile-warnings (%clcw-warnings-p
                                                    %clcw-failure-p &optional
                                                    %clcw-context-format
                                                    %clcw-context-arguments)
  (when %clcw-failure-p
    (case uiop/lisp-build:*compile-file-failure-behaviour*
      (:warn (warn 'uiop/lisp-build:compile-failed-warning
                   :description "Lisp compilation failed"
                   :context-format %clcw-context-format
                   :context-arguments %clcw-context-arguments))
      (:error (error 'uiop/lisp-build:compile-failed-error
                     :description "Lisp compilation failed"
                     :context-format %clcw-context-format
                     :context-arguments %clcw-context-arguments))
      (:ignore nil)))
  (when %clcw-warnings-p
    (case uiop/lisp-build:*compile-file-warnings-behaviour*
      (:warn (warn 'uiop/lisp-build:compile-warned-warning
                   :description "Lisp compilation had style-warnings"
                   :context-format %clcw-context-format
                   :context-arguments %clcw-context-arguments))
      (:error (error 'uiop/lisp-build:compile-warned-error
                     :description "Lisp compilation had style-warnings"
                     :context-format %clcw-context-format
                     :context-arguments %clcw-context-arguments))
      (:ignore nil))))

(defun uiop/lisp-build:check-lisp-compile-results (%clcr-output %clcr-warnings-p
                                                   %clcr-failure-p &optional
                                                   %clcr-context-format
                                                   %clcr-context-arguments)
  (unless %clcr-output
    (error 'uiop/lisp-build:compile-file-error
           :context-format %clcr-context-format
           :context-arguments %clcr-context-arguments))
  (uiop/lisp-build:check-lisp-compile-warnings %clcr-warnings-p %clcr-failure-p
                                               %clcr-context-format
                                               %clcr-context-arguments))

;;; The muffled-conditions family. Each call-with is the uiop/utility
;;; call-with-muffled-conditions over the union of the uninteresting lists the
;;; load/build halves skip; the with-* macros are Java expansions over them
;;; (LispMacroExpander), like every other uiop macro.
(defun uiop/lisp-build:call-with-muffled-compiler-conditions (%cmcc-thunk)
  (uiop/utility:call-with-muffled-conditions %cmcc-thunk
   (append uiop/lisp-build:*uninteresting-conditions*
           uiop/lisp-build:*uninteresting-compiler-conditions*)))

(defun uiop/lisp-build:call-with-muffled-loader-conditions (%cmlc-thunk)
  (uiop/utility:call-with-muffled-conditions %cmlc-thunk
   (append uiop/lisp-build:*uninteresting-conditions*
           uiop/lisp-build:*uninteresting-loader-conditions*)))

;;; call-around-hook: call the hook around the function, over uiop/utility's
;;; call-function (upstream's shape).
(defun uiop/lisp-build:call-around-hook (%cah-hook %cah-function)
  (uiop/utility:call-function (or %cah-hook 'funcall) %cah-function))

;;; The load half. load-pathname / current-lisp-file-pathname read the CL
;;; specials a spliced load brackets (%begin-file / %end-file, .kb/load-inliner.md);
;;; *compile-file-pathname* is permanently nil, so current-lisp-file-pathname is
;;; the load pathname. load* is the muffled loader around cl:load for a
;;; pathname/string, and over uiop/stream:eval-input for a stream (rontolisp's
;;; load cannot load from a string-input-stream, so the stream arm is the one
;;; upstream picks for the implementations that cannot either).
(defun uiop/lisp-build:load-pathname () cl:*load-pathname*)

(defun uiop/lisp-build:current-lisp-file-pathname ()
  (or cl:*compile-file-pathname* cl:*load-pathname*))

(defun uiop/lisp-build:load* (%ld-x &rest %ld-keys &key &allow-other-keys)
  (uiop/lisp-build:call-with-muffled-loader-conditions
   (lambda ()
     (if (or (pathnamep %ld-x) (stringp %ld-x))
         (apply 'load %ld-x %ld-keys)
         (uiop/stream:eval-input %ld-x)))))

(defun uiop/lisp-build:load-from-string (%lfs-string)
  (with-input-from-string (%lfs-s %lfs-string) (uiop/lisp-build:load* %lfs-s)))

;;; Pathname plumbing. lispize-pathname is make-pathname with the type forced to
;;; "lisp"; compile-file-pathname* has no compiled-file TYPE to derive (see
;;; compile-file-type), so the honest answer is the explicit output-file merged
;;; against the input's defaults, or nil when none is given -- there is no
;;; default compiled-output pathname to compute.
(defun uiop/lisp-build:lispize-pathname (%lp-input-file)
  (make-pathname :type "lisp" :defaults %lp-input-file))

(defun uiop/lisp-build:compile-file-pathname* (%cfp-input-file &rest %cfp-keys
                                               &key
                                               ((:output-file %cfp-output-file))
                                               &allow-other-keys)
  (declare (ignore %cfp-keys))
  (if %cfp-output-file
      (uiop/pathname:merge-pathnames* %cfp-output-file
                                      (make-pathname
                                       :defaults
                                       (uiop/pathname:merge-pathnames*
                                        %cfp-input-file)))
      nil))

;;; Warnings-file plumbing. rontolisp has no deferred warnings and no
;;; implementation-specific warnings-file type: warnings-file-type answers nil
;;; for the one implementation here (the case has no :rontolisp clause), so
;;; warnings-file-p answers nil and the *warnings-file-type* variable stays nil.
(defun uiop/lisp-build:warnings-file-type (&optional %wft-implementation-type)
  (case (or %wft-implementation-type uiop/os:*implementation-type*)
    ((:acl :allegro) "allegro-warnings")
    ((:cmu :cmucl) "cmucl-warnings")
    ((:sbcl) "sbcl-warnings")
    ((:clozure :ccl) "ccl-warnings")
    ((:scl) "scl-warnings")))

(defun uiop/lisp-build:warnings-file-p
    (%wfp-file &optional %wfp-implementation-type)
  (let ((%wfp-type
         (if %wfp-implementation-type
             (uiop/lisp-build:warnings-file-type %wfp-implementation-type)
             uiop/lisp-build:*warnings-file-type*)))
    (when %wfp-type (equal (pathname-type %wfp-file) %wfp-type))))

;;; The deferred-warnings check is a defensive no-op: a library calls
;;; enable/disable/reset around a build it expects might warn, and an error
;;; there would convert a no-op into a failure. rontolisp has no deferred
;;; warnings to enable, disable or reset.
(defun uiop/lisp-build:enable-deferred-warnings-check () nil)
(defun uiop/lisp-build:disable-deferred-warnings-check () nil)
(defun uiop/lisp-build:reset-deferred-warnings () nil)

;;; The compile-file* and deferred-warnings machinery. rontolisp has no
;;; compile-file, no fasl and no compilation-unit protocol -- its compilers
;;; write a .class or a .wasm from the CLI -- so these resolve and signal
;;; not-implemented-error naming the operation, with the reason. Re-evaluation
;;; trigger for .kb/uiop.md: if rontolisp ever grows a real cl:compile-file,
;;; compile-file* is the first thing that should stop signalling.
(defun uiop/lisp-build:compile-file*
    (%cf-input-file &rest %cf-keys &key &allow-other-keys)
  (declare (ignore %cf-input-file %cf-keys))
  (uiop/utility:not-implemented-error "UIOP/LISP-BUILD:COMPILE-FILE*"
   "rontolisp compiles a whole program and has no compile-file or fasl"))

(defun uiop/lisp-build:save-deferred-warnings (%sdw-warnings-file)
  (declare (ignore %sdw-warnings-file))
  (uiop/utility:not-implemented-error "UIOP/LISP-BUILD:SAVE-DEFERRED-WARNINGS"
                                      "deferred warnings carry compilation-unit warnings, and rontolisp has no compilation-unit protocol"))

(defun uiop/lisp-build:reify-deferred-warnings ()
  (uiop/utility:not-implemented-error "UIOP/LISP-BUILD:REIFY-DEFERRED-WARNINGS"
                                      "deferred warnings carry compilation-unit warnings, and rontolisp has no compilation-unit protocol"))

(defun uiop/lisp-build:unreify-deferred-warnings (%urdw-reified)
  (declare (ignore %urdw-reified))
  (uiop/utility:not-implemented-error
   "UIOP/LISP-BUILD:UNREIFY-DEFERRED-WARNINGS"
   "deferred warnings carry compilation-unit warnings, and rontolisp has no compilation-unit protocol"))

(defun uiop/lisp-build:check-deferred-warnings
    (%cdw-files &rest %cdw-keys &key &allow-other-keys)
  (declare (ignore %cdw-files %cdw-keys))
  (uiop/utility:not-implemented-error "UIOP/LISP-BUILD:CHECK-DEFERRED-WARNINGS"
                                      "deferred warnings carry compilation-unit warnings, and rontolisp has no compilation-unit protocol"))

(defun uiop/lisp-build:combine-fasls (%cf-inputs %cf-output)
  (declare (ignore %cf-inputs %cf-output))
  (uiop/utility:not-implemented-error "UIOP/LISP-BUILD:COMBINE-FASLS"
   "combining fasls needs a fasl format, and rontolisp has no compile-file"))

;; with-saved-deferred-warnings is upstream's macro over
;; call-with-saved-deferred-warnings; both are in the not-implemented group, so
;; this is a defun stub (a call form is lowered by
;; LispMacroExpander.expandUnimplementedUiopMacro, dropping the body it was
;; handed) and only #'uiop:with-saved-deferred-warnings as a value reaches it.
(defun uiop/lisp-build:with-saved-deferred-warnings
    (%wsdw-rest &rest %wsdw-body)
  (declare (ignore %wsdw-rest %wsdw-body))
  (uiop/utility:not-implemented-error
   "UIOP/LISP-BUILD:WITH-SAVED-DEFERRED-WARNINGS"
   "deferred warnings carry compilation-unit warnings, and rontolisp has no compilation-unit protocol"))

;;; reify-simple-sexp / unreify-simple-sexp: a pure sexp <-> portable
;;; representation, over the same atoms and cons cells. A symbol reifies through
;;; uiop/package:reify-symbol, which signals (the reify/unreify pair is part of
;;; the image-upgrade surgery); the round trip is pinned on the atoms and cons
;;; cells, which are the portable part.
;;; The terminating NIL of a proper list is a SYMBOL, and reify-symbol signals;
;;; the round trip is pinned on the portable part, so nil passes through as nil
;;; (a non-nil symbol still reifies through reify-symbol).
(defun uiop/lisp-build:reify-simple-sexp (%rss-sexp)
  (cond ((null %rss-sexp) nil)
        ((symbolp %rss-sexp) (uiop/package:reify-symbol %rss-sexp))
        ((or (numberp %rss-sexp) (characterp %rss-sexp) (stringp %rss-sexp)
             (pathnamep %rss-sexp))
         %rss-sexp)
        ((consp %rss-sexp)
         (cons (uiop/lisp-build:reify-simple-sexp (car %rss-sexp))
               (uiop/lisp-build:reify-simple-sexp (cdr %rss-sexp))))
        ((and (arrayp %rss-sexp) (not (stringp %rss-sexp)))
         (coerce
          (mapcar 'uiop/lisp-build:reify-simple-sexp (coerce %rss-sexp 'list))
          'vector))
        (t (error "REIFY-SIMPLE-SEXP: not a simple sexp ~S" %rss-sexp))))

(defun uiop/lisp-build:unreify-simple-sexp (%urss-sexp)
  (cond ((null %urss-sexp) nil)
   ((or (symbolp %urss-sexp) (numberp %urss-sexp) (characterp %urss-sexp)
        (stringp %urss-sexp) (pathnamep %urss-sexp))
    %urss-sexp)
   ((consp %urss-sexp)
    (cons (uiop/lisp-build:unreify-simple-sexp (car %urss-sexp))
          (uiop/lisp-build:unreify-simple-sexp (cdr %urss-sexp))))
   ((and (arrayp %urss-sexp) (not (stringp %urss-sexp))
         (= (length %urss-sexp) 2))
    (uiop/package:unreify-symbol %urss-sexp))
   ((and (arrayp %urss-sexp) (not (stringp %urss-sexp)))
    (coerce
     (mapcar 'uiop/lisp-build:unreify-simple-sexp (coerce %urss-sexp 'list))
     'vector))
   (t (error "UNREIFY-SIMPLE-SEXP: not a portable simple sexp ~S" %urss-sexp))))
