;;;; uiop/utility -- the portable helper package.
;;;;
;;;; Canonical shape (home-package-qualified public names, bare cl names), so it
;;;; needs no package resolution. Only the names rontolisp really implements live
;;;; here; every other uiop/utility export gets a not-implemented-error stub
;;;; synthesized from the inventory (UiopLibrary). See .kb/uiop.md.

;;; The two conditions that give "we cannot do that here" a name. They are the
;;; first thing any other uiop item needs, so they are real from the start --
;;; every stub in every sub-package signals not-implemented-error, and no uiop
;;; name is allowed to reach a caller as "undefined function".
;;;
;;; Upstream reports the implementation it is running on by calling
;;; uiop:implementation-type; there is one implementation here, so the report
;;; names rontolisp directly rather than routing through a lookup that could only
;;; ever answer one thing.
(define-condition uiop/utility:not-implemented-error (error)
  ((functionality :initarg :functionality
                  :initform nil
                  :reader uiop/utility::%not-implemented-functionality)
   (format-control :initarg :format-control
                   :initform nil
                   :reader uiop/utility::%not-implemented-format-control)
   (format-arguments :initarg :format-arguments
                     :initform nil
                     :reader uiop/utility::%not-implemented-format-arguments))
  (:report
   (lambda (%nie-c %nie-s)
     (write-string "Not (currently) implemented on rontolisp: " %nie-s)
     (write-string
      (%princ-piece (uiop/utility::%not-implemented-functionality %nie-c))
      %nie-s)
     (let ((%nie-fc (uiop/utility::%not-implemented-format-control %nie-c)))
       (when %nie-fc
         (write-string " " %nie-s)
         (write-string (apply #'format nil %nie-fc
                              (uiop/utility::%not-implemented-format-arguments
                               %nie-c)) %nie-s))))))

(defun uiop/utility:not-implemented-error (%nie-functionality &optional
                                           %nie-format-control &rest
                                           %nie-format-arguments)
  (error 'uiop/utility:not-implemented-error
         :functionality %nie-functionality
         :format-control %nie-format-control
         :format-arguments %nie-format-arguments))

(define-condition uiop/utility:parameter-error (error)
  ((functionality :initarg :functionality
                  :initform nil
                  :reader uiop/utility::%parameter-error-functionality)
   (format-control :initarg :format-control
                   :initform nil
                   :reader uiop/utility::%parameter-error-format-control)
   (format-arguments :initarg :format-arguments
                     :initform nil
                     :reader uiop/utility::%parameter-error-format-arguments))
  (:report
   (lambda (%pe-c %pe-s)
     (write-string (apply #'format nil
                    (uiop/utility::%parameter-error-format-control %pe-c)
                    (uiop/utility::%parameter-error-functionality %pe-c)
                    (uiop/utility::%parameter-error-format-arguments %pe-c))
                   %pe-s))))

;; The functionality is the SECOND argument, as upstream: the format-control
;; takes it as its first format argument (a caller that does not want it there
;; skips it with ~*).
(defun uiop/utility:parameter-error
    (%pe-format-control %pe-functionality &rest %pe-format-arguments)
  (error 'uiop/utility:parameter-error
         :functionality %pe-functionality
         :format-control %pe-format-control
         :format-arguments %pe-format-arguments))

;;; The debug-utility trio. The VARIABLE holds upstream's own default form (it
;;; is data -- a form to be evaluated for a pathname), but the LOADER cannot run
;;; here: loading a developer's personal debug file needs a run-time load of a
;;; COMPUTED pathname, and load is a compile-time splice on every backend
;;; (.kb/load-inliner.md). So it names itself in a not-implemented-error rather
;;; than pretending. uiop-debug is the macro that calls it (LispMacroExpander).
(defvar uiop/utility:*uiop-debug-utility*
  '(uiop/package:symbol-call :uiop :subpathname
                             (uiop/package:symbol-call :uiop :uiop-directory)
                             "contrib/debug.lisp"))

(defun uiop/utility:load-uiop-debug-utility
    (&key ((:package %ludu-package)) ((:utility-file %ludu-utility-file)))
  (declare (ignore %ludu-package %ludu-utility-file))
  (uiop/utility:not-implemented-error "UIOP/UTILITY:LOAD-UIOP-DEBUG-UTILITY"
                                      "loading a debug utility needs a run-time LOAD of a computed pathname; load is a compile-time splice here"))

;;; Macro definition helper. The one function here whose subtle bug shows up as
;;; someone else's macro misbehaving, so the ordering is upstream's exactly: a
;;; docstring counts only when DOCUMENTATION is true AND something follows it
;;; (a lone string is the body, not documentation), declarations may precede and
;;; follow it, and a second docstring is an error naming WHOLE when given.
(defun uiop/utility:parse-body
    (%pb-body &key ((:documentation %pb-documentation)) ((:whole %pb-whole)))
  (let ((%pb-doc nil) (%pb-decls nil) (%pb-current nil))
    (tagbody
      %pb-declarations
      (setq %pb-current (car %pb-body))
      (when (and %pb-documentation (stringp %pb-current) (cdr %pb-body))
        (when %pb-doc
          (error "Too many documentation strings in ~S."
                 (or %pb-whole %pb-body)))
        (setq %pb-doc %pb-current)
        (setq %pb-body (cdr %pb-body))
        (go %pb-declarations))
      (when (and (listp %pb-current) (eql (first %pb-current) 'declare))
        (setq %pb-decls (cons %pb-current %pb-decls))
        (setq %pb-body (cdr %pb-body))
        (go %pb-declarations)))
    (values %pb-body (nreverse %pb-decls) %pb-doc)))

;;; List manipulation.
(defun uiop/utility:length=n-p (%lnp-x %lnp-n)
  (check-type %lnp-n (integer 0 *))
  (let ((%lnp-l %lnp-x) (%lnp-i %lnp-n))
    (do ()
        (nil)
      (cond ((zerop %lnp-i) (return (null %lnp-l)))
            ((not (consp %lnp-l)) (return nil))
            (t
             (setq %lnp-l (cdr %lnp-l))
             (setq %lnp-i (- %lnp-i 1)))))))

(defun uiop/utility:ensure-list (%el-x) (if (listp %el-x) %el-x (list %el-x)))

;;; Plists: remove a key, i.e. keyword-argument cleanup.
(defun uiop/utility:remove-plist-key (%rpk-key %rpk-plist)
  (let ((%rpk-acc nil) (%rpk-rest %rpk-plist))
    (do ()
        ((null %rpk-rest) (nreverse %rpk-acc))
      (unless (eq (car %rpk-rest) %rpk-key)
        (setq %rpk-acc (cons (cadr %rpk-rest) (cons (car %rpk-rest) %rpk-acc))))
      (setq %rpk-rest (cddr %rpk-rest)))))

(defun uiop/utility:remove-plist-keys (%rpks-keys %rpks-plist)
  (let ((%rpks-acc nil) (%rpks-rest %rpks-plist))
    (do ()
        ((null %rpks-rest) (nreverse %rpks-acc))
      (unless (member (car %rpks-rest) %rpks-keys)
        (setq %rpks-acc
              (cons (cadr %rpks-rest) (cons (car %rpks-rest) %rpks-acc))))
      (setq %rpks-rest (cddr %rpks-rest)))))

;;; Sequence / character utilities, bodies VERBATIM from upstream utility.lisp:
;;; pure Lisp one-liners over primitives every backend has. quri's render-uri
;;; calls all three to decide whether to insert a path slash.
(defun uiop/utility:emptyp (x)
  (or (null x) (and (vectorp x) (zerop (length x)))))

(defun uiop/utility:first-char (s)
  (and (stringp s) (plusp (length s)) (char s 0)))

(defun uiop/utility:last-char (s)
  (and (stringp s) (plusp (length s)) (char s (1- (length s)))))

;; Upstream's semantics (split on ANY character of the separator sequence,
;; scanning right to left so :max keeps the UNsplit head: ("a.b.c" :max 2 ->
;; ("a.b" "c")), empty string -> ("")), rewritten without upstream's
;; flet-return-from-outer-block shape: a `return` inside `do` would exit do's own
;; nil block, so the loop carries an explicit done flag instead. sxql's
;; sql-symbol tokenizer calls it on every dotted column name.
(defun uiop/utility:split-string (string &key max (separator '(#\Space #\Tab)))
  (let ((end (length string)))
    (if (zerop end)
        (list "")
        (let ((parts nil) (words 0) (done nil))
          (do ()
              (done)
            (if (and max (>= words (1- max)))
                (setq done t)
                (let ((start
                       (position-if (lambda (c) (find c separator)) string
                                    :end end
                                    :from-end t)))
                  (if (null start)
                      (setq done t)
                      (progn
                        (setq parts (cons (subseq string (1+ start) end) parts))
                        (setq words (1+ words))
                        (setq end start))))))
          (cons (subseq string 0 end) parts)))))

;;; Characters. Upstream's quartet exists because base-char and character are
;;; DIFFERENT types on ECL, LispWorks, SBCL and Genera, so a string's element
;;; type has to be discovered. rontolisp has ONE character type -- (subtypep
;;; 'character 'base-char) and (subtypep 'base-char 'character) both answer t --
;;; and running upstream's own derivation on that lattice is what these answers
;;; are: the loop over '(base-char character) drops base-char (character is a
;;; subtype of it), so the vector is #(character), the max index is 0, and
;;; +non-base-chars-exist-p+ is (plusp 0) = nil. That is what makes the whole
;;; group consistent: base-string-p is upstream's (and) = t for any string, and
;;; strings-common-element-type is the constant 'character. See .kb/uiop.md.
(defparameter uiop/utility:+character-types+ (vector 'character))

(defparameter uiop/utility:+max-character-type-index+ 0)

(defconstant uiop/utility:+non-base-chars-exist-p+ nil)

(defun uiop/utility:character-type-index (%cti-x)
  (declare (ignore %cti-x))
  0)

(defun uiop/utility:base-string-p (%bsp-string)
  (declare (ignore %bsp-string))
  t)

(defun uiop/utility:strings-common-element-type (%scet-strings)
  (declare (ignore %scet-strings))
  'character)

;;; Strings. reduce/strcat accumulates with concatenate rather than upstream's
;;; preallocated make-string + replace: sizing the buffer up front buys nothing
;;; when there is one element type, and concatenate is the one string builder
;;; every backend has (the uiop/stream reader is built the same way).
(defun uiop/utility:reduce/strcat
    (%rs-strings &key ((:key %rs-key)) ((:start %rs-start)) ((:end %rs-end)))
  (let ((%rs-list %rs-strings) (%rs-out ""))
    (when (or %rs-start %rs-end)
      (setq %rs-list (subseq %rs-list (or %rs-start 0) %rs-end)))
    (when %rs-key
      (setq %rs-list
            (mapcar (lambda (%rs-e) (funcall %rs-key %rs-e)) %rs-list)))
    (dolist (%rs-in %rs-list %rs-out)
      (setq %rs-out
            (concatenate 'string %rs-out
                         (etypecase %rs-in
                           (null "")
                           (character (string %rs-in))
                           (string %rs-in)))))))

(defun uiop/utility:strcat (&rest %sc-strings)
  (uiop/utility:reduce/strcat %sc-strings))

(defun uiop/utility:string-prefix-p (%spp-prefix %spp-string)
  (let* ((%spp-x (string %spp-prefix))
         (%spp-y (string %spp-string))
         (%spp-lx (length %spp-x))
         (%spp-ly (length %spp-y)))
    (and (<= %spp-lx %spp-ly) (string= %spp-x %spp-y :end2 %spp-lx))))

(defun uiop/utility:string-suffix-p (%ssp-string %ssp-suffix)
  (let* ((%ssp-x (string %ssp-string))
         (%ssp-y (string %ssp-suffix))
         (%ssp-lx (length %ssp-x))
         (%ssp-ly (length %ssp-y)))
    (and (<= %ssp-ly %ssp-lx)
         (string= %ssp-x %ssp-y :start1 (- %ssp-lx %ssp-ly)))))

(defun uiop/utility:string-enclosed-p (%sep-prefix %sep-string %sep-suffix)
  (and (uiop/utility:string-prefix-p %sep-prefix %sep-string)
       (uiop/utility:string-suffix-p %sep-string %sep-suffix)))

;; Each built from character literals alone: a defvar whose initializer READS
;; another global is exactly the shape that has been recorded as reading the
;; wrong value, and the splice order here (inventory order: +cr+, +crlf+, +lf+) would
;; not put +cr+ and +lf+ before +crlf+ anyway.
(defvar uiop/utility:+cr+ (string #\Return))

(defvar uiop/utility:+lf+ (string #\Newline))

(defvar uiop/utility:+crlf+
  (concatenate 'string (string #\Return) (string #\Newline)))

(defun uiop/utility:stripln (%sl-x)
  (check-type %sl-x string)
  (cond ((uiop/utility:string-suffix-p %sl-x uiop/utility:+crlf+)
         (values (subseq %sl-x 0 (- (length %sl-x) 2)) uiop/utility:+crlf+))
        ((uiop/utility:string-suffix-p %sl-x uiop/utility:+lf+)
         (values (subseq %sl-x 0 (- (length %sl-x) 1)) uiop/utility:+lf+))
        ((uiop/utility:string-suffix-p %sl-x uiop/utility:+cr+)
         (values (subseq %sl-x 0 (- (length %sl-x) 1)) uiop/utility:+cr+))
        (t (values %sl-x nil))))

;; The reader upcases, so "an ANSI CL platform" is the only platform here and
;; the modern-syntax arm upstream carries for Allegro is dropped.
(defun uiop/utility:standard-case-symbol-name (%scsn-name)
  (check-type %scsn-name (or string symbol))
  (if (symbolp %scsn-name) (string %scsn-name) (string-upcase %scsn-name)))

(defun uiop/utility:find-standard-case-symbol
    (%fscs-name %fscs-package &optional (%fscs-error t))
  (uiop/package:find-symbol* (uiop/utility:standard-case-symbol-name %fscs-name)
                             (if (stringp %fscs-package)
                                 (uiop/utility:standard-case-symbol-name
                                  %fscs-package)
                                 %fscs-package) %fscs-error))

;; for each substring in SUBSTRINGS, find occurrences of it within STRING that
;; don't use parts of matched occurrences of previous strings, and FROB them:
;; remove if FROB is nil, replace by FROB if it is a string, or call FROB with
;; the match and an emitter function. Upstream accumulates into a
;; string-output-stream and returns the ORIGINAL string object when nothing was
;; frobbed (a return-from out of a labels function, which is a cross-lambda exit
;; here and would put the whole program in EH mode on WASM); this returns a
;; fresh string that is string= to it instead.
(defun uiop/utility:frob-substrings
    (%fs-string %fs-substrings &optional %fs-frob)
  (labels ((%fs-recurse (%fs-subs %fs-start %fs-end)
             (cond ((>= %fs-start %fs-end) "")
                   ((null %fs-subs) (subseq %fs-string %fs-start %fs-end))
                   (t
                    (let* ((%fs-spec (first %fs-subs))
                           (%fs-sub
                            (if (consp %fs-spec) (car %fs-spec) %fs-spec))
                           (%fs-fun
                            (if (consp %fs-spec) (cdr %fs-spec) %fs-frob))
                           (%fs-more (rest %fs-subs))
                           (%fs-found
                            (search %fs-sub %fs-string
                                    :start2 %fs-start
                                    :end2 %fs-end)))
                      (if %fs-found
                          (concatenate 'string
                           (%fs-recurse %fs-more %fs-start %fs-found)
                           (cond ((null %fs-fun) "")
                                 ((stringp %fs-fun) %fs-fun)
                                 (t
                                  (let ((%fs-acc ""))
                                    (funcall %fs-fun %fs-sub
                                             (lambda (%fs-x &optional (%fs-s 0)
                                                      (%fs-e (length %fs-x)))
                                               (setq %fs-acc
                                                     (concatenate 'string
                                                                  %fs-acc
                                                                  (subseq %fs-x
                                                                   %fs-s
                                                                   %fs-e)))))
                                    %fs-acc)))
                           (%fs-recurse %fs-subs (+ %fs-found (length %fs-sub))
                                        %fs-end))
                          (%fs-recurse %fs-more %fs-start %fs-end)))))))
    (%fs-recurse %fs-substrings 0 (length %fs-string))))

;;; Timestamps: a REAL, or a boolean where t = -infinity and nil = +infinity.
(defun uiop/utility:timestamp< (%ts-x %ts-y)
  (cond ((eq %ts-x t) (not (eq %ts-y t)))
        ((null %ts-x) nil)
        ((eq %ts-y t) nil)
        ((null %ts-y) t)
        (t (< %ts-x %ts-y))))

;; Upstream's own answer, and worth naming because it surprises: the comparison
;; chain starts at nil = +infinity, so (timestamp< nil y) is false for every y
;; and the list is "increasing" only when it is EMPTY.
(defun uiop/utility:timestamps< (%tss-list)
  (let ((%tss-x nil))
    (dolist (%tss-y %tss-list t)
      (unless (uiop/utility:timestamp< %tss-x %tss-y) (return nil))
      (setq %tss-x %tss-y))))

(defun uiop/utility:timestamp*< (&rest %tsv-list)
  (uiop/utility:timestamps< %tsv-list))

(defun uiop/utility:timestamp<= (%tle-x %tle-y)
  (not (uiop/utility:timestamp< %tle-y %tle-x)))

(defun uiop/utility:earlier-timestamp (%et-x %et-y)
  (if (uiop/utility:timestamp< %et-x %et-y) %et-x %et-y))

(defun uiop/utility:timestamps-earliest (%tse-list)
  (let ((%tse-acc nil))
    (dolist (%tse-x %tse-list %tse-acc)
      (setq %tse-acc (uiop/utility:earlier-timestamp %tse-acc %tse-x)))))

(defun uiop/utility:earliest-timestamp (&rest %ets-list)
  (uiop/utility:timestamps-earliest %ets-list))

(defun uiop/utility:later-timestamp (%lt-x %lt-y)
  (if (uiop/utility:timestamp< %lt-x %lt-y) %lt-y %lt-x))

(defun uiop/utility:timestamps-latest (%tsl-list)
  (let ((%tsl-acc t))
    (dolist (%tsl-x %tsl-list %tsl-acc)
      (setq %tsl-acc (uiop/utility:later-timestamp %tsl-acc %tsl-x)))))

(defun uiop/utility:latest-timestamp (&rest %lts-list)
  (uiop/utility:timestamps-latest %lts-list))

;;; Function designators. The :package keyword of ensure-function is accepted
;;; and ignored: read-from-string reads into the current package on every
;;; backend, and there is no run-time *package* rebinding to hang it on.
(defun uiop/utility:ensure-function (%ef-fun &key ((:package %ef-package) :cl))
  (declare (ignore %ef-package))
  (etypecase %ef-fun
    (function %ef-fun)
    ((or boolean keyword character number pathname) (constantly %ef-fun))
    (hash-table (lambda (%ef-x) (gethash %ef-x %ef-fun)))
    (symbol (fdefinition %ef-fun))
    (cons (if (eq 'lambda (car %ef-fun))
              (eval %ef-fun)
              (lambda (&rest %ef-args)
                (apply (car %ef-fun) (append (cdr %ef-fun) %ef-args)))))
    (string (eval (list 'function (read-from-string %ef-fun))))))

(defun uiop/utility:access-at (%aa-object %aa-at)
  (flet ((%aa-access (%aa-o %aa-accessor)
           (etypecase %aa-accessor
             (function (funcall %aa-accessor %aa-o))
             (integer (elt %aa-o %aa-accessor))
             (keyword (getf %aa-o %aa-accessor))
             (null %aa-o)
             (symbol (funcall %aa-accessor %aa-o))
             (cons
              (funcall (uiop/utility:ensure-function %aa-accessor) %aa-o)))))
    (if (listp %aa-at)
        (let ((%aa-o %aa-object))
          (dolist (%aa-a %aa-at %aa-o) (setq %aa-o (%aa-access %aa-o %aa-a))))
        (%aa-access %aa-object %aa-at))))

(defun uiop/utility:access-at-count (%aac-at)
  (cond ((integerp %aac-at) (1+ %aac-at))
        ((and (consp %aac-at) (integerp (first %aac-at))) (1+ (first %aac-at)))
        (t nil)))

(defun uiop/utility:call-function (%cf-spec &rest %cf-arguments)
  (apply (uiop/utility:ensure-function %cf-spec) %cf-arguments))

(defun uiop/utility:call-functions (%cfs-specs)
  (dolist (%cfs-spec %cfs-specs nil) (uiop/utility:call-function %cfs-spec)))

;; The one member of uiop/utility that needs a primitive no backend has:
;; pushing onto a hook means (setf (symbol-value VARIABLE) ...) over a variable
;; named at RUN time, and symbol-value is read-only on all four backends (it is
;; not a setf place, and there is no cl:set). Naming that is the honest answer;
;; the alternative -- silently dropping the hook -- would make an image-hook
;; caller believe it registered something. See .kb/uiop.md.
(defun uiop/utility:register-hook-function
    (%rhf-variable %rhf-hook &optional %rhf-call-now-p)
  (declare (ignore %rhf-variable %rhf-hook %rhf-call-now-p))
  (uiop/utility:not-implemented-error "UIOP/UTILITY:REGISTER-HOOK-FUNCTION"
                                      "pushing onto a hook needs (setf (symbol-value ...)), which is not a place on any backend"))

;;; CLOS. The class designator algebra, over rontolisp's find-class: a keyword
;;; is looked up as a name in PACKAGE, a string is read as a symbol, a class
;;; object and nil designate themselves, any other symbol names a class. Two
;;; deliberate narrowings of upstream: the keyword lookup does NOT fall back to
;;; *package* (which the resolver turns into a compile-time constant -- it has no
;;; run-time value to read here, and upstream calls that arm backward
;;; compatibility anyway), and the :super test compares type NAMES rather than
;;; class objects, because subtypep here takes type specifiers.
(defun uiop/utility:coerce-class (%cc-class &key ((:package %cc-package) :cl)
                                            ((:super %cc-super) t)
                                            ((:error %cc-error) 'error))
  (let* ((%cc-normalized
          (cond ((keywordp %cc-class)
                 (uiop/package:find-symbol* %cc-class %cc-package nil))
                ((stringp %cc-class) (read-from-string %cc-class))
                (t %cc-class)))
         (%cc-found
          (cond ((null %cc-normalized) nil)
                ((keywordp %cc-normalized) nil)
                ((symbolp %cc-normalized) (find-class %cc-normalized nil nil))
                (t %cc-normalized))))
    (or (and %cc-found
             (or (eq %cc-super t)
                 (and %cc-super (symbolp %cc-super) (not (keywordp %cc-super))
                      (find-class %cc-super nil nil)
                      (subtypep %cc-normalized %cc-super))) %cc-found)
        (uiop/utility:call-function %cc-error
         "Can't coerce ~S to a ~:[class~;subclass of ~:*~S~]" %cc-class
         %cc-super))))

;;; Hash-tables.
(defun uiop/utility:ensure-gethash (%eg-key %eg-table %eg-default)
  (multiple-value-bind (%eg-value %eg-foundp) (gethash %eg-key %eg-table)
    (values (if %eg-foundp
                %eg-value
                (setf (gethash %eg-key %eg-table)
                      (uiop/utility:call-function %eg-default))) %eg-foundp)))

(defun uiop/utility:list-to-hash-set (%lths-list)
  (let ((%lths-h (make-hash-table :test 'equal)))
    (dolist (%lths-x %lths-list %lths-h) (setf (gethash %lths-x %lths-h) t))))

;;; Lexicographic comparison of lists, element< a strict total order.
(defun uiop/utility:lexicographic< (%lx-element< %lx-x %lx-y)
  (cond ((null %lx-y) nil)
        ((null %lx-x) t)
        ((funcall %lx-element< (car %lx-x) (car %lx-y)) t)
        ((funcall %lx-element< (car %lx-y) (car %lx-x)) nil)
        (t (uiop/utility:lexicographic< %lx-element< (cdr %lx-x) (cdr %lx-y)))))

(defun uiop/utility:lexicographic<= (%lxe-element< %lxe-x %lxe-y)
  (not (uiop/utility:lexicographic< %lxe-element< %lxe-y %lxe-x)))

;;; Simple style warnings.
(define-condition uiop/utility:simple-style-warning
    (simple-condition style-warning)
  ())

(defun uiop/utility:style-warn (%swn-datum &rest %swn-arguments)
  (cond ((stringp %swn-datum)
         (warn
          (make-condition 'uiop/utility:simple-style-warning
                          :format-control %swn-datum
                          :format-arguments %swn-arguments)))
        ((symbolp %swn-datum)
         (assert (subtypep %swn-datum 'style-warning))
         (apply #'warn %swn-datum %swn-arguments))
        (t (apply #'warn %swn-datum %swn-arguments))))

;;; Condition control. match-condition-p's STRING pattern compares against
;;; simple-condition-format-control, which here answers the ALREADY FORMATTED
;;; message (rontolisp builds the message at signal time), so a pattern with
;;; format directives in it cannot match -- a pattern without them still does.
;;; See .kb/uiop.md.
(defun uiop/utility:match-condition-p (%mcp-x %mcp-condition)
  (cond ((symbolp %mcp-x) (typep %mcp-condition %mcp-x))
        ((functionp %mcp-x) (funcall %mcp-x %mcp-condition))
        ((stringp %mcp-x)
         (and (typep %mcp-condition 'simple-condition)
              (ignore-errors
                (equal (simple-condition-format-control %mcp-condition)
                       %mcp-x))))
        ((and (vectorp %mcp-x) (= (length %mcp-x) 2))
         (ignore-errors
           (typep %mcp-condition
            (uiop/package:find-symbol* (svref %mcp-x 0) (svref %mcp-x 1) nil))))
        (t (error "Invalid condition pattern ~S" %mcp-x))))

(defun uiop/utility:match-any-condition-p (%macp-condition %macp-conditions)
  (dolist (%macp-x %macp-conditions nil)
    (let ((%macp-hit (uiop/utility:match-condition-p %macp-x %macp-condition)))
      (when %macp-hit (return %macp-hit)))))

(defun uiop/utility:call-with-muffled-conditions (%cwmc-thunk %cwmc-conditions)
  (handler-bind ((t
                  (lambda (%cwmc-c)
                    (when (uiop/utility:match-any-condition-p %cwmc-c
                                                              %cwmc-conditions)
                      (muffle-warning %cwmc-c)))))
    (funcall %cwmc-thunk)))

;;; Feature expressions: the two forms #+ / #- test as always-true and
;;; always-false.
(defun uiop/utility:boolean-to-feature-expression (%btfe-value)
  (if %btfe-value '(:and) '(:or)))

(defun uiop/utility:symbol-test-to-feature-expression
    (%sttfe-name %sttfe-package)
  (uiop/utility:boolean-to-feature-expression
   (uiop/package:find-symbol* %sttfe-name %sttfe-package nil)))
