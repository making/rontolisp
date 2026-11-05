;; What a compiled eval answers for a built-in macro: the table the compile-time
;; expander has no run-time half of (.kb/eval-runtime.md). EvalMacroRows splices the
;; part of it a program's forms can reach and generates the dispatch.
;;
;; A defun named %eval-row-NAME expands the form (NAME . args) into forms _eval knows,
;; and one named %eval-place-NAME expands an assignment of the place (NAME . args). _eval
;; evaluates what they answer in the environment the form stood in, so an expansion
;; reaches its own temporaries as lexical variables and the program's forms as
;; closures. The temporaries are fixed names: an inner expansion's binding shadows an
;; outer one only inside the inner form, and no form of the program names them.
;;
;; A quoted symbol naming a row brings that row along. The data of a definition named
;; %eval-soft-* does not: it names a row only when the form it is looking at spells what
;; that row serves, which the program's forms have then spelled too. That is what keeps
;; a dolist with no return out of the wasm EH mode a block's catch needs.

(defvar %eval-specials% nil)

;; ---- helpers ----

;; Whether a list among FORMS, at any depth, is headed by one of NAMES: what EvalReferences
;; counts as spelling an operator, so a row this answers for is there.
(defun %eval-call-in (forms names)
  (and (consp forms)
       (or (%eval-call-p (car forms) names) (%eval-call-in (cdr forms) names))))

(defun %eval-call-p (form names)
  (and (consp form)
       (or (and (member (car form) names) t) (%eval-call-in form names))))

;; FORM inside the block a do, a dolist or a local function establishes, when BODY
;; can leave it.
(defun %eval-soft-block (name form body)
  (if (%eval-call-in body '(return return-from)) (list 'block name form) form))

;; The forms of an iteration's body, a tagbody when a go can reach its tags.
(defun %eval-soft-body (body)
  (if (%eval-call-in body '(go)) (list (cons 'tagbody body)) body))

;; (name init) of a binding, every shape a lambda-less binding list allows.
(defun %eval-binding (binding)
  (if (consp binding)
      (list (car binding) (if (cdr binding) (cadr binding) nil))
      (list binding nil)))

;; The assignments of a do's step forms: in parallel, or in order for do*.
(defun %eval-do-steps (vars sequential)
  (let ((stepped (remove-if-not (lambda (v) (and (consp v) (cddr v))) vars)))
    (cond ((null stepped) nil)
          ((or sequential (null (cdr stepped)))
           (mapcar (lambda (v) (list 'setq (car v) (caddr v))) stepped))
          (t (list
              (list 'let
                    (list
                     (list '%eval-steps
                      (cons 'list (mapcar (lambda (v) (caddr v)) stepped))))
                    (cons 'setq
                          (%eval-nth-assignments
                           (mapcar (lambda (v) (car v)) stepped) 0))))))))

;; (a (nth 0 %eval-steps) b (nth 1 %eval-steps) ...) of the setq above.
(defun %eval-nth-assignments (names k)
  (if names
      (cons (car names)
            (cons (list 'nth k '%eval-steps)
                  (%eval-nth-assignments (cdr names) (+ k 1))))
      nil))

(defun %eval-iteration (args let-operator sequential)
  (let ((vars (car args)) (end (cadr args)) (body (cddr args)))
    (%eval-soft-block nil
                      `(,let-operator ,(mapcar #'%eval-binding vars)
                        (while (not ,(car end))
                          ,@(%eval-soft-body body)
                          ,@(%eval-do-steps vars sequential)) ,@(cdr end))
                      body)))

;; A place's argument forms evaluated once, into one list a place reads back with nth:
;; a list of the (nth k %eval-place) forms standing for each argument. The plist of a
;; getf is a place itself, so it stays.
(defun %eval-once-place (place k)
  (if (consp place)
      (if (eq (car place) 'getf)
          (cons (car place)
                (cons (cadr place) (%eval-once-arguments (cddr place) k)))
          (cons (car place) (%eval-once-arguments (cdr place) k)))
      place))

(defun %eval-once-arguments (forms k)
  (if forms
      (cons (list 'nth k '%eval-place)
            (%eval-once-arguments (cdr forms) (+ k 1)))
      nil))

(defun %eval-arguments-of (place)
  (if (consp place) (if (eq (car place) 'getf) (cddr place) (cdr place)) nil))

;; (let ((%eval-place (list args...))) FORM) over PLACES read back through %eval-once-place,
;; FORM built by BUILD from those places.
(defun %eval-with-places (places build)
  (let ((arguments nil) (rewritten nil) (k 0))
    (dolist (place places)
      (setq rewritten (cons (%eval-once-place place k) rewritten))
      (let ((own (%eval-arguments-of place)))
        (setq arguments (append arguments own))
        (setq k (+ k (length own)))))
    (let ((form (funcall build (reverse rewritten))))
      (if arguments
          (list 'let (list (list '%eval-place (cons 'list arguments))) form)
          form))))

(defun %eval-step-place (args operator)
  (%eval-with-places (list (car args))
                     (lambda (places)
                       (let ((place (car places)))
                         `(setf ,place
                                (,operator ,place
                                 ,(if (cdr args) (cadr args) 1)))))))

;; ---- binding and sequencing ----

(defun %eval-row-let* (args env)
  (let ((bindings (car args)))
    (if (and bindings (cdr bindings))
        `(let (,(car bindings)) (let* ,(cdr bindings) ,@(cdr args)))
        (cons 'let args))))

(defun %eval-row-prog1 (args env)
  `(let ((%eval-first ,(car args)))
     ,@(cdr args)
     %eval-first))

(defun %eval-row-prog2 (args env)
  `(progn
     ,(car args)
     (prog1 ,@(cdr args))))

(defun %eval-row-the (args env) (cadr args))

(defun %eval-row-locally (args env) (cons 'progn args))

(defun %eval-row-declare (args env) nil)

(defun %eval-row-load-time-value (args env) (car args))

(defun %eval-row-with-compilation-unit (args env) (cons 'progn (cdr args)))

(defun %eval-row-eval-when (args env)
  (if (%eval-execute-situation-p (car args)) (cons 'progn (cdr args)) nil))

;; The name, not the symbol, of the old situation: a quoted eval would read as a use of
;; eval to everything that asks whether a program runs forms.
(defun %eval-execute-situation-p (situations)
  (and situations
       (or (eq (car situations) :execute)
           (string= (symbol-name (car situations)) "EVAL")
           (%eval-execute-situation-p (cdr situations)))))

(defun %eval-row-progv (args env)
  `(%eval-progv ,(car args) ,(cadr args) (lambda () ,@(cddr args))))

(defun %eval-progv (names vals thunk) (progv names vals (funcall thunk)))

;; ---- iteration ----

(defun %eval-row-do (args env) (%eval-iteration args 'let nil))

(defun %eval-row-do* (args env) (%eval-iteration args 'let* t))

(defun %eval-row-dolist (args env)
  (let ((var (car (car args))) (body (cdr args)))
    (%eval-soft-block nil
                      `(let ((%eval-cursor ,(cadr (car args))))
                         (while (consp %eval-cursor)
                           (let ((,var (car %eval-cursor)))
                             ,@(%eval-soft-body body))
                           (setq %eval-cursor (cdr %eval-cursor)))
                         (let ((,var nil)) ,(caddr (car args)))) body)))

(defun %eval-row-dotimes (args env)
  (let ((var (car (car args))) (body (cdr args)))
    (%eval-soft-block nil
                      `(let ((,var 0) (%eval-limit ,(cadr (car args))))
                         (while (< ,var %eval-limit)
                           ,@(%eval-soft-body body)
                           (setq ,var (+ ,var 1)))
                         ,(caddr (car args))) body)))

;; The simple loop; the keyword clauses are the compile-time expander's alone.
(defun %eval-row-loop (args env)
  (if (%eval-simple-loop-p args)
      `(block nil (while t ,@args))
      (error "EVAL: the keyword clauses of LOOP are compile-time only")))

(defun %eval-simple-loop-p (forms)
  (or (null forms) (and (consp (car forms)) (%eval-simple-loop-p (cdr forms)))))

;; ---- conditionals ----

(defun %eval-row-case (args env) (%eval-case args nil nil))

(defun %eval-row-ecase (args env) (%eval-case args "ECASE" nil))

(defun %eval-row-ccase (args env) (%eval-case args "ECASE" nil))

(defun %eval-row-typecase (args env) (%eval-case args nil t))

(defun %eval-row-etypecase (args env) (%eval-case args "ETYPECASE" t))

(defun %eval-row-ctypecase (args env) (%eval-case args "ETYPECASE" t))

;; EXHAUSTIVE names the operator a value no clause matches is reported under.
(defun %eval-case (args exhaustive types)
  `(let ((%eval-key ,(car args)))
     (cond
      ,@(mapcar
         (lambda (clause) (%eval-case-clause clause exhaustive types))
         (cdr args))
      ,@(if
         exhaustive
         `((t (%eval-no-clause ,exhaustive %eval-key)))
         nil))))

(defun %eval-case-clause (clause exhaustive types)
  (cons (%eval-case-test (car clause) exhaustive types)
        (if (cdr clause) (cdr clause) (list nil))))

(defun %eval-case-test (keys exhaustive types)
  (cond ((and (not exhaustive) (or (eq keys t) (eq keys 'otherwise))) t)
   (types `(typep %eval-key ',keys))
   ((listp keys) (cons 'or (mapcar (lambda (key) `(eql %eval-key ',key)) keys)))
   (t `(eql %eval-key ',keys))))

(defun %eval-no-clause (operator value)
  (error "~A: no clause matches ~S" operator value))

(defun %eval-row-assert (args env)
  (if (caddr args)
      `(if ,(car args) nil (%eval-assert-failed ,@(cddr args)))
      `(if ,(car args)
           nil
           (%eval-assert-failed "The assertion ~S failed." ',(car args)))))

(defun %eval-assert-failed (control &rest arguments)
  (error "~A" (apply #'format nil control arguments)))

(defun %eval-row-check-type (args env)
  `(let ((%eval-key ,(car args)))
     (if (typep %eval-key ',(cadr args))
         nil
         (%eval-check-type-failed %eval-key ',(cadr args) ',(car args)
                                  ,(if (cddr args) (caddr args) nil)))))

(defun %eval-check-type-failed (value type place description)
  (error 'simple-type-error
         :datum value
         :expected-type type
         :format-control "The value of ~A is ~S, which is not ~A."
         :format-arguments (list place value
                            (or description (format nil "of type ~A" type)))))

;; ---- assignment ----

(defun %eval-row-incf (args env) (%eval-step-place args '+))

(defun %eval-row-decf (args env) (%eval-step-place args '-))

(defun %eval-row-pushnew (args env)
  (%eval-with-places (list (cadr args))
                     (lambda (places)
                       (let ((place (car places)))
                         `(let ((%eval-item ,(car args)))
                            (if (member %eval-item ,place ,@(cddr args))
                                ,place
                                (setf ,place (cons %eval-item ,place))))))))

(defun %eval-row-remf (args env)
  (%eval-with-places (list (car args))
                     (lambda (places)
                       (let ((place (car places)))
                         `(let ((%eval-removed
                                 (%eval-plist-without ,place ,(cadr args))))
                            (setf ,place (cdr %eval-removed))
                            (car %eval-removed))))))

;; (found . plist) of PLIST without INDICATOR's first entry.
(defun %eval-plist-without (plist indicator)
  (cond ((null plist) (cons nil nil))
        ((eq (car plist) indicator) (cons t (cddr plist)))
        (t (let ((rest (%eval-plist-without (cddr plist) indicator)))
             (cons (car rest)
                   (cons (car plist) (cons (cadr plist) (cdr rest))))))))

;; (setf a v b w) of the values (%eval-values) holds, read back by position.
(defun %eval-parallel-places (places k)
  (if places
      (cons (list 'setf (car places) (list 'nth k '%eval-values))
            (%eval-parallel-places (cdr places) (+ k 1)))
      nil))

(defun %eval-every-other (list)
  (if list (cons (car list) (%eval-every-other (cddr list))) nil))

(defun %eval-row-psetq (args env)
  `(let ((%eval-values (list ,@(%eval-every-other (cdr args)))))
     ,@(%eval-parallel-places (%eval-every-other args) 0)
     nil))

(defun %eval-row-psetf (args env)
  `(let ((%eval-values (list ,@(%eval-every-other (cdr args)))))
     ,@(%eval-parallel-places (%eval-every-other args) 0)
     nil))

(defun %eval-row-rotatef (args env)
  (%eval-with-places args
                     (lambda (places)
                       `(let ((%eval-values (list ,@places)))
                          ,@(%eval-parallel-places (butlast places) 1)
                          (setf ,(car (last places)) (nth 0 %eval-values))
                          nil))))

(defun %eval-row-shiftf (args env)
  (%eval-with-places (butlast args)
                     (lambda (places)
                       `(let ((%eval-values (list ,@places ,(car (last args)))))
                          ,@(%eval-parallel-places places 1)
                          (car %eval-values)))))

;; ---- definitions ----

(defun %eval-row-defvar (args env)
  `(%eval-define ',(car args) ,(if (cdr args) `(lambda () ,(cadr args)) nil) nil
                 t))

(defun %eval-row-defparameter (args env)
  `(%eval-define ',(car args) (lambda () ,(cadr args)) t t))

(defun %eval-row-defconstant (args env)
  `(%eval-define ',(car args) (lambda () ,(cadr args)) nil nil))

(defun %eval-define (name thunk always special)
  (if (and special (not (member name %eval-specials%)))
      (setq %eval-specials% (cons name %eval-specials%)))
  (if (and thunk (or always (not (boundp name)))) (set name (funcall thunk)))
  name)

(defun %eval-special-p (name)
  (or (member name %eval-specials%) (%eval-compiled-special-p name)))

(defun %eval-declared-special-p (name body)
  (and (consp body) (consp (car body)) (eq (car (car body)) 'declare)
       (or (%eval-spells-special name (cdr (car body)))
           (%eval-declared-special-p name (cdr body)))))

(defun %eval-spells-special (name specifiers)
  (and specifiers
       (or (and (consp (car specifiers)) (eq (car (car specifiers)) 'special)
                (member name (cdr (car specifiers))))
           (%eval-spells-special name (cdr specifiers)))))

;; What _eval's let evaluates instead when a name it binds is special: the variable is
;; bound by name, as progv does, and every other name lexically. Nil for a let binding
;; no special. The inits run first, in order, in the environment the let stands in.
(defun %eval-special-let (args)
  (let ((bindings (mapcar #'%eval-binding (car args)))
        (body (cdr args))
        (special-names nil)
        (special-values nil)
        (lexical nil)
        (k 0))
    (dolist (binding bindings)
      (let ((value (list 'nth k '%eval-inits)))
        (if (or (%eval-special-p (car binding))
                (%eval-declared-special-p (car binding) body))
            (progn
              (setq special-names (cons (car binding) special-names))
              (setq special-values (cons value special-values)))
            (setq lexical (cons (list (car binding) value) lexical))))
      (setq k (+ k 1)))
    (if special-names
        `(let ((%eval-inits
                (list ,@(mapcar (lambda (binding) (cadr binding)) bindings))))
           (let ,(reverse lexical)
             (%eval-progv ',(reverse special-names)
                          (list ,@(reverse special-values))
                          (lambda () ,@body))))
        nil)))

;; ---- multiple values ----

(defun %eval-values-of (thunk) (multiple-value-list (funcall thunk)))

(defun %eval-values-form (form) `(%eval-values-of (lambda () ,form)))

(defun %eval-nth-bindings (names k)
  (if names
      (cons (list (car names) (list 'nth k '%eval-values))
            (%eval-nth-bindings (cdr names) (+ k 1)))
      nil))

(defun %eval-row-multiple-value-list (args env) (%eval-values-form (car args)))

(defun %eval-row-multiple-value-bind (args env)
  `(let ((%eval-values ,(%eval-values-form (cadr args))))
     (let ,(%eval-nth-bindings (car args) 0) ,@(cddr args))))

(defun %eval-row-multiple-value-setq (args env)
  `(let ((%eval-values ,(%eval-values-form (cadr args))))
     ,@(%eval-parallel-places (car args) 0)
     (car %eval-values)))

(defun %eval-row-multiple-value-call (args env)
  `(apply ,(car args) (append ,@(mapcar #'%eval-values-form (cdr args)))))

(defun %eval-row-multiple-value-prog1 (args env)
  `(let ((%eval-values ,(%eval-values-form (car args))))
     ,@(cdr args)
     (values-list %eval-values)))

(defun %eval-row-nth-value (args env)
  `(nth ,(car args) ,(%eval-values-form (cadr args))))

;; ---- destructuring-bind ----

(defun %eval-row-destructuring-bind (args env)
  `(let* ((%eval-cursors (list ,(cadr args)))
          ,@(%eval-destructure
             (car args)
             :required (%eval-required-count (car args))
             (%eval-positional-count (car args))
             0))
     ,@(cddr args)))

;; The parameters before the first lambda-list keyword.
(defun %eval-required-count (lambda-list)
  (if (and (consp lambda-list)
           (not
            (member (car lambda-list)
                    '(&optional &rest &body &key &aux &allow-other-keys))))
      (+ (if (eq (car lambda-list) '&whole) 0 1)
         (%eval-required-count (cdr lambda-list)))
      0))

;; The required and optional parameters.
(defun %eval-positional-count (lambda-list)
  (if (and (consp lambda-list)
           (not
            (member (car lambda-list)
                    '(&rest &body &key &aux &allow-other-keys))))
      (+ (if (eq (car lambda-list) '&optional) 0 1)
         (%eval-positional-count (cdr lambda-list)))
      0))

;; The let* bindings of a destructuring lambda list. %eval-cursors is a stack of the
;; lists being walked, its head the current one: each binding rebinds it to advance, a
;; nested list pushes its element and pops it when its own parameters are done. MIN and
;; MAX are this level's counts and K the parameters walked so far, what a wrong count
;; reports.
(defun %eval-destructure (rest mode min max k)
  (cond ((null rest)
         (if (or (eq mode :required) (eq mode :optional))
             `((%eval-ignored (%eval-no-more (car %eval-cursors) ,max ,k)))
             nil))
        ((symbolp rest) `((,rest (car %eval-cursors))))
        ((eq (car rest) '&optional)
         (%eval-destructure (cdr rest) :optional min max k))
        ((or (eq (car rest) '&rest) (eq (car rest) '&body)
             (eq (car rest) '&whole))
         (cons (list (cadr rest) '(car %eval-cursors))
               (%eval-destructure (cddr rest)
                                  (if (eq (car rest) '&whole) mode :rested) min
                                  max k)))
        ((or (eq (car rest) '&key) (eq (car rest) '&allow-other-keys))
         (%eval-destructure (cdr rest) :key min max k))
        ((eq (car rest) '&aux) (mapcar #'%eval-binding (cdr rest)))
        ((eq mode :required)
         (append (%eval-destructure-required (car rest) min k)
                 (%eval-destructure (cdr rest) mode min max (+ k 1))))
        ((eq mode :optional)
         (append (%eval-destructure-optional (car rest))
                 (%eval-destructure (cdr rest) mode min max (+ k 1))))
        ((eq mode :key)
         (cons (%eval-destructure-key (car rest))
               (%eval-destructure (cdr rest) mode min max k)))
        (t (%eval-destructure (cdr rest) mode min max k))))

(defun %eval-advance ()
  '(%eval-cursors (cons (cdr (car %eval-cursors)) (cdr %eval-cursors))))

(defun %eval-destructure-required (parameter min k)
  (append (if (consp parameter)
              (append `((%eval-cursors
                         (cons (%eval-required (car %eval-cursors) ,min ,k)
                               %eval-cursors)))
                      (%eval-destructure parameter
                       :required (%eval-required-count parameter)
                       (%eval-positional-count parameter) 0)
                      '((%eval-cursors (cdr %eval-cursors))))
              `((,parameter (%eval-required (car %eval-cursors) ,min ,k))))
          (list (%eval-advance))))

(defun %eval-destructure-optional (parameter)
  (let ((name (if (consp parameter) (car parameter) parameter))
        (default (if (consp parameter) (cadr parameter) nil))
        (supplied (if (consp parameter) (caddr parameter) nil)))
    (append (if supplied `((,supplied (if (car %eval-cursors) t nil))) nil)
     `((,name (if (car %eval-cursors) (car (car %eval-cursors)) ,default)))
     (list (%eval-advance)))))

(defun %eval-destructure-key (parameter)
  (let* ((spec (if (consp parameter) (car parameter) parameter))
         (name (if (consp spec) (cadr spec) spec))
         (key (if (consp spec) (car spec) spec))
         (default (if (consp parameter) (cadr parameter) nil)))
    `(,name
      (%eval-key-value (car %eval-cursors) ,(symbol-name key)
                       (lambda () ,default)))))

(defun %eval-key-value (plist name default)
  (let ((tail plist))
    (while (and tail
                (not
                 (and (symbolp (car tail))
                      (string= (symbol-name (car tail)) name))))
      (setq tail (cddr tail)))
    (if tail (cadr tail) (funcall default))))

(defun %eval-required (cursor min k)
  (if (consp cursor) (car cursor) (%eval-wrong-count "least" min k)))

(defun %eval-no-more (cursor max k)
  (if cursor (%eval-wrong-count "most" max (+ k (length cursor))) nil))

(defun %eval-wrong-count (bound limit got)
  (%program-error
   (format nil "Function expects at ~A ~A argument~A, got ~A" bound limit
           (if (= limit 1) "" "s") got)))

;; ---- local functions ----

;; The local functions a form sees, the alist _eval's operator lookup reads.
(defun %eval-functions-of (env)
  (let ((binding (assoc '%eval-functions env))) (if binding (cdr binding) nil)))

(defun %eval-local-function (definition)
  `(lambda ,(cadr definition)
     ,(%eval-soft-block (car definition) (cons 'progn (cddr definition))
                        (cddr definition))))

(defun %eval-row-flet (args env)
  `(let ((%eval-functions
          (append (list
                   ,@(mapcar (lambda (definition)
                               `(cons ',(car definition)
                                      ,(%eval-local-function definition)))
                             (car args))) ',(%eval-functions-of env))))
     ,@(cdr args)))

(defun %eval-row-labels (args env)
  `(let ((%eval-functions
          (append ',(mapcar (lambda (definition) (cons (car definition) nil))
                            (car args)) ',(%eval-functions-of env))))
     (%eval-bind-functions %eval-functions
                           (list ,@(mapcar #'%eval-local-function (car args))))
     ,@(cdr args)))

;; Each cell of the alist's head takes its function: the closures, built under the alist
;; itself, see one another through it.
(defun %eval-bind-functions (cells functions)
  (if functions
      (progn
        (rplacd (car cells) (car functions))
        (%eval-bind-functions (cdr cells) (cdr functions)))
      nil))

;; ---- non-local exits ----

;; The enclosing exit point of KIND serving NAME, innermost first: a binding of
;; %eval-exit holds (block NAME) or (tagbody TAGS), fresh per evaluation, and its
;; identity is the catch tag.
(defun %eval-find-exit (kind name env)
  (let ((tail env) (found nil))
    (while (and tail (not found))
      (let ((binding (car tail)))
        (if (and (eq (car binding) '%eval-exit) (eq (car (cdr binding)) kind))
            (if (if (eq kind 'block)
                    (eq (cadr (cdr binding)) name)
                    (member name (cadr (cdr binding))))
                (setq found (cdr binding)))))
      (setq tail (cdr tail)))
    found))

(defun %eval-row-block (args env)
  `(let ((%eval-exit (list 'block ',(car args))))
     (%eval-catch %eval-exit (lambda () ,@(cdr args)))))

(defun %eval-row-return-from (args env)
  (let ((exit (%eval-find-exit 'block (car args) env)))
    (if exit
        (%eval-throw-form (list 'quote exit) (cadr args))
        (error "RETURN-FROM: no enclosing block named ~A" (car args)))))

(defun %eval-row-return (args env) `(return-from nil ,(car args)))

(defun %eval-row-catch (args env)
  `(%eval-catch ,(car args) (lambda () ,@(cdr args))))

(defun %eval-row-throw (args env) (%eval-throw-form (car args) (cadr args)))

;; The tag first; the value's values travel as a list, a call's argument being one value.
(defun %eval-throw-form (tag value)
  `(%eval-throw-values ,tag (multiple-value-list ,value)))

(defun %eval-catch (tag thunk) (catch tag (funcall thunk)))

(defun %eval-throw-values (tag vals) (throw tag (values-list vals)))

(defun %eval-throw (tag value) (throw tag value))

(defun %eval-row-unwind-protect (args env)
  `(%eval-unwind-protect (lambda () ,(car args)) (lambda () ,@(cdr args))))

(defun %eval-unwind-protect (protected cleanup)
  (unwind-protect (funcall protected) (funcall cleanup)))

;; The statements between two tags run as one closure: a go throws its tag to the
;; tagbody's own loop, which resumes at the segment that tag opens.
(defun %eval-row-tagbody (args env)
  `(let ((%eval-exit (list 'tagbody ',(%eval-tags args))))
     (%eval-run-tagbody %eval-exit
                        (list
                         ,@(mapcar (lambda (segment)
                                     `(cons ',(car segment)
                                            (lambda () ,@(cdr segment))))
                                   (%eval-segments args))))))

(defun %eval-tags (statements) (remove-if #'consp statements))

;; ((tag-or-nil statement...) ...), the statements before the first tag first.
(defun %eval-segments (statements)
  (let ((segments nil) (current (list nil)))
    (dolist (statement statements)
      (if (consp statement)
          (setq current (append current (list statement)))
          (progn
            (setq segments (cons current segments))
            (setq current (list statement)))))
    (reverse (cons current segments))))

(defun %eval-run-tagbody (exit segments)
  (let ((cursor segments))
    (while cursor
      (let ((target
             (catch exit
               (funcall (cdr (car cursor)))
               '%eval-fell-through)))
        (setq cursor
              (if (eq target '%eval-fell-through)
                  (cdr cursor)
                  (%eval-segment-of target segments)))))
    nil))

(defun %eval-segment-of (tag segments)
  (if (eql (car (car segments)) tag)
      segments
      (%eval-segment-of tag (cdr segments))))

(defun %eval-row-go (args env)
  (let ((exit (%eval-find-exit 'tagbody (car args) env)))
    (if exit
        `(%eval-throw ',exit ',(car args))
        (error "GO: no enclosing tagbody holds the tag ~A" (car args)))))

(defun %eval-row-prog (args env)
  `(block nil (let ,(car args) (tagbody ,@(cdr args)))))

(defun %eval-row-prog* (args env)
  `(block nil (let* ,(car args) (tagbody ,@(cdr args)))))

;; ---- conditions ----

(defun %eval-row-handler-case (args env)
  (let ((clauses
         (remove-if (lambda (clause) (eq (car clause) :no-error)) (cdr args)))
        (no-error (assoc :no-error (cdr args))))
    `(%eval-handler-case (lambda () ,(car args))
                         (list ,@(mapcar #'%eval-handler-clause clauses))
                         ,(if no-error (cons 'lambda (cdr no-error)) nil))))

(defun %eval-handler-clause (clause)
  `(cons ',(car clause)
         (lambda ,(or (cadr clause) '(%eval-condition)) ,@(cddr clause))))

(defun %eval-handler-case (protected clauses no-error)
  (let* ((tag (list nil))
         (hit nil)
         (answers
          (catch tag
            (handler-bind ((condition
                            (lambda (c)
                              (let ((clause (%eval-clause-of c clauses)))
                                (if clause
                                    (progn
                                      (setq hit (cons clause c))
                                      (throw tag nil)))))))
              (multiple-value-list (funcall protected))))))
    (cond (hit (funcall (cdr (car hit)) (cdr hit)))
          (no-error (apply no-error answers))
          (t (values-list answers)))))

(defun %eval-clause-of (condition clauses)
  (cond ((null clauses) nil)
        ((typep condition (car (car clauses))) (car clauses))
        (t (%eval-clause-of condition (cdr clauses)))))

(defun %eval-row-ignore-errors (args env)
  `(handler-case (progn ,@args)
     (error (%eval-condition) (values nil %eval-condition))))

(defun %eval-row-handler-bind (args env)
  `(%eval-handler-bind (list
                        ,@(mapcar (lambda (binding)
                                    `(cons ',(car binding) ,(cadr binding)))
                                  (car args))) (lambda () ,@(cdr args))))

(defun %eval-handler-bind (handlers protected)
  (handler-bind ((condition
                  (lambda (c)
                    (dolist (handler handlers)
                      (if (typep c (car handler)) (funcall (cdr handler) c))))))
    (funcall protected)))

;; ---- streams ----

(defun %eval-row-with-open-file (args env)
  (let ((var (car (car args))))
    `(let ((,var (open ,@(cdr (car args)))))
       (unwind-protect (progn ,@(cdr args)) (if ,var (close ,var))))))

(defun %eval-row-with-open-stream (args env)
  (let ((var (car (car args))))
    `(let ((,var ,(cadr (car args))))
       (unwind-protect (progn ,@(cdr args)) (close ,var)))))

(defun %eval-row-with-output-to-string (args env)
  (let ((var (car (car args))) (target (cadr (car args))))
    (if target
        `(let ((,var (make-string-output-stream)))
           (prog1 (progn ,@(cdr args))
             (%eval-extend-string ,target (get-output-stream-string ,var))))
        `(let ((,var (make-string-output-stream)))
           ,@(cdr args)
           (get-output-stream-string ,var)))))

(defun %eval-extend-string (target contents)
  (dotimes (i (length contents)) (vector-push-extend (char contents i) target)))

(defun %eval-row-with-input-from-string (args env)
  (let* ((options (cdr (car args))) (keywords (cdr options)))
    `(let ((,(car (car args))
            (make-string-input-stream
             (subseq ,(car options) ,(or (getf keywords :start) 0)
                     ,(getf keywords :end)))))
       ,@(cdr args))))

(defun %eval-row-with-standard-io-syntax (args env)
  `(%eval-with-standard-io-syntax (lambda () ,@args)))

(defun %eval-with-standard-io-syntax (thunk)
  (with-standard-io-syntax (funcall thunk)))

(defun %eval-row-time (args env)
  `(let ((%eval-start (get-internal-real-time)))
     (let ((%eval-result ,(car args)))
       (%eval-report-time %eval-start)
       %eval-result)))

(defun %eval-report-time (start)
  (princ "; Elapsed real time: ")
  (princ (- (get-internal-real-time) start))
  (princ " ms")
  (terpri))

(defun %eval-row-with-hash-table-iterator (args env)
  `(let ((%eval-entries (%eval-hash-entries ,(cadr (car args)))))
     (flet ((,(car (car args)) ()
              (if %eval-entries
                  (let ((%eval-entry (car %eval-entries)))
                    (setq %eval-entries (cdr %eval-entries))
                    (values t (car %eval-entry) (cdr %eval-entry)))
                  (values nil nil nil))))
       ,@(cdr args))))

(defun %eval-hash-entries (table)
  (let ((entries nil))
    (maphash (lambda (key value) (setq entries (cons (cons key value) entries)))
             table)
    entries))

;; The symbols of a package, walked as a list so a form's return and its result take the
;; dolist path.
(defun %eval-row-do-symbols (args env)
  `(dolist (,(car (car args)) (%eval-symbols-of ,(cadr (car args)))
            ,(caddr (car args)))
     ,@(cdr args)))

(defun %eval-symbols-of (package)
  (let ((symbols nil))
    (do-symbols (symbol (or package *package*))
      (setq symbols (cons symbol symbols)))
    symbols))

(defun %eval-row-do-external-symbols (args env)
  `(dolist (,(car (car args)) (%eval-external-symbols-of ,(cadr (car args)))
            ,(caddr (car args)))
     ,@(cdr args)))

(defun %eval-external-symbols-of (package)
  (let ((symbols nil))
    (do-external-symbols (symbol (or package *package*))
      (setq symbols (cons symbol symbols)))
    symbols))

(defun %eval-row-do-all-symbols (args env)
  `(dolist (,(car (car args)) (%eval-all-symbols) ,(cadr (car args)))
     ,@(cdr args)))

(defun %eval-all-symbols ()
  (let ((symbols nil))
    (do-all-symbols (symbol) (setq symbols (cons symbol symbols)))
    symbols))

(defun %eval-row-print-unreadable-object (args env)
  (let ((options (cddr (car args))))
    `(%eval-print-unreadable ,(car (car args)) ,(cadr (car args))
                             ,(getf options :type) ,(getf options :identity)
                             (lambda () ,@(cdr args)))))

(defun %eval-print-unreadable (object stream type identity thunk)
  (if type
      (if identity
          (print-unreadable-object (object stream :type t :identity t)
            (funcall thunk))
          (print-unreadable-object (object stream :type t) (funcall thunk)))
      (if identity
          (print-unreadable-object (object stream :identity t) (funcall thunk))
          (print-unreadable-object (object stream) (funcall thunk)))))

;; ---- places _store has no arm for ----

(defun %eval-place-gethash (args value)
  `(%eval-store-gethash ,(car args) ,(cadr args) ',value))

(defun %eval-store-gethash (key table value) (setf (gethash key table) value))

(defun %eval-place-aref (args value) `(%eval-store-aref ',value ,@args))

(defun %eval-store-aref (value array &rest subscripts)
  (if (cdr subscripts)
      (setf (aref array (car subscripts) (cadr subscripts)) value)
      (setf (aref array (car subscripts)) value)))

(defun %eval-place-svref (args value) `(%eval-store-aref ',value ,@args))

(defun %eval-place-elt (args value)
  `(%eval-store-elt ,(car args) ,(cadr args) ',value))

(defun %eval-store-elt (sequence index value) (setf (elt sequence index) value))

(defun %eval-place-char (args value)
  `(%eval-store-char ,(car args) ,(cadr args) ',value))

(defun %eval-place-schar (args value)
  `(%eval-store-char ,(car args) ,(cadr args) ',value))

(defun %eval-store-char (string index value) (setf (char string index) value))

(defun %eval-place-getf (args value)
  `(progn
     (setf ,(car args) (%eval-plist-with ,(car args) ,(cadr args) ',value))
     ',value))

(defun %eval-plist-with (plist indicator value)
  (setf (getf plist indicator) value)
  plist)

(defun %eval-place-get (args value)
  `(%eval-store-get ,(car args) ,(cadr args) ',value))

(defun %eval-store-get (symbol indicator value)
  (setf (get symbol indicator) value))

(defun %eval-place-rest (args value) `(setf (cdr ,(car args)) ',value))

(defun %eval-place-slot-value (args value)
  `(%eval-store-slot-value ,(car args) ,(cadr args) ',value))

(defun %eval-store-slot-value (instance name value)
  (setf (slot-value instance name) value))
