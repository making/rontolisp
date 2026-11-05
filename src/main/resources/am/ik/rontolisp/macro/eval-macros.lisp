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

(defun %eval-row-loop (args env)
  (if (%eval-simple-loop-p args)
      `(block nil (while t ,@args))
      (%eval-loop-expansion args)))

(defun %eval-simple-loop-p (forms)
  (or (null forms) (and (consp (car forms)) (%eval-simple-loop-p (cdr forms)))))

;; ---- loop's keyword clauses ----

;; LispMacroExpander.LoopExpander over the forms _eval runs, keeping its ordering
;; (.kb/loop-iteration-heads.md): one while, its test ANDing each clause's head in source
;; order. A state is an alist of what the clauses contribute. The hidden variables of one
;; loop are places of the one list it binds, (nth k %eval-loop): a nested loop's list
;; shadows it only inside that loop's form, and no name is made per clause.

(defun %eval-loop-get (state key) (cdr (assoc key state)))

(defun %eval-loop-put (state key value)
  (rplacd (assoc key state) value)
  value)

(defun %eval-loop-add (state key form)
  (%eval-loop-put state key (append (%eval-loop-get state key) (list form))))

(defun %eval-loop-add-all (state key forms)
  (%eval-loop-put state key (append (%eval-loop-get state key) forms)))

(defun %eval-loop-state (tokens)
  (list (cons :tokens tokens) (cons :count 0) (cons :bindings nil)
        (cons :initially nil) (cons :body nil) (cons :steps nil)
        (cons :heads nil) (cons :finally nil) (cons :first nil)
        (cons :named nil) (cons :driver-ends nil) (cons :group-drivers nil)
        (cons :equals-groups nil) (cons :accumulator nil)
        (cons :accumulator-init nil) (cons :accumulator-reverse nil)
        (cons :termination nil) (cons :into nil)))

(defun %eval-loop-piece ()
  (list (cons :binds nil) (cons :steps nil) (cons :syncs nil)
        (cons :post-steps nil) (cons :prefix nil) (cons :ends nil)))

(defun %eval-loop-temp (state)
  (let ((k (%eval-loop-get state :count)))
    (%eval-loop-put state :count (+ k 1))
    (list 'nth k '%eval-loop)))

(defun %eval-loop-temp-p (form)
  (and (consp form) (eq (car form) 'nth) (eq (caddr form) '%eval-loop)))

(defun %eval-loop-variable-p (form)
  (or (symbolp form) (%eval-loop-temp-p form)))

;; ---- tokens ----

;; A keyword by its name, in whatever package the loop was read: for, :for and #:for.
(defun %eval-loop-keyword (token)
  (and token (symbolp token)
       (car
        (member (symbol-name token)
                '("FOR" "AS" "FROM" "UPFROM" "DOWNFROM" "TO" "UPTO" "BELOW"
                  "DOWNTO" "ABOVE" "BY" "IN" "ON" "=" "THEN" "ACROSS" "REPEAT"
                  "WHILE" "UNTIL" "DO" "DOING" "COLLECT" "COLLECTING" "APPEND"
                  "APPENDING" "NCONC" "NCONCING" "SUM" "SUMMING" "COUNT"
                  "COUNTING" "MAXIMIZE" "MAXIMIZING" "MINIMIZE" "MINIMIZING"
                  "WHEN" "IF" "UNLESS" "ELSE" "END" "FINALLY" "INITIALLY"
                  "RETURN" "WITH" "AND" "INTO" "THEREIS" "ALWAYS" "NEVER"
                  "OF-TYPE" "NAMED" "BEING")
                :test #'string=))))

(defun %eval-loop-word-in (word names)
  (and word (member word names :test #'string=) t))

(defun %eval-loop-plain-name (token)
  (if (symbolp token) (symbol-name token) nil))

(defun %eval-loop-peek (state)
  (let ((tokens (%eval-loop-get state :tokens)))
    (if tokens (%eval-loop-keyword (car tokens)) nil)))

(defun %eval-loop-peek-is (state names)
  (%eval-loop-word-in (%eval-loop-peek state) names))

(defun %eval-loop-skip (state)
  (%eval-loop-put state :tokens (cdr (%eval-loop-get state :tokens))))

(defun %eval-loop-next (state)
  (let ((tokens (%eval-loop-get state :tokens)))
    (if (null tokens) (error "loop: unexpected end of clauses"))
    (%eval-loop-skip state)
    (car tokens)))

;; The forms up to the next clause keyword.
(defun %eval-loop-forms (state)
  (let ((forms nil))
    (while (and (%eval-loop-get state :tokens) (null (%eval-loop-peek state)))
      (setq forms (cons (%eval-loop-next state) forms)))
    (if forms (reverse forms) (list nil))))

;; A type specifier carries nothing here: of-type TYPE, or a bare fixnum, float, t or nil.
(defun %eval-loop-skip-type (state)
  (let ((tokens (%eval-loop-get state :tokens)))
    (cond ((null tokens) nil)
          ((%eval-loop-peek-is state '("OF-TYPE"))
           (%eval-loop-skip state)
           (%eval-loop-next state))
          ((or (null (car tokens)) (eq (car tokens) t)) (%eval-loop-skip state))
          ((and (null (%eval-loop-peek state))
                (%eval-loop-word-in (%eval-loop-plain-name (car tokens))
                                    '("FIXNUM" "FLOAT")))
           (%eval-loop-skip state)))))

;; ---- forms the expansion is made of ----

(defun %eval-loop-set (variable value) (list 'setf variable value))

(defun %eval-loop-or (tests) (if (cdr tests) (cons 'or tests) (car tests)))

(defun %eval-loop-progn-t (forms) (cons 'progn (append forms (list t))))

(defun %eval-loop-exit (state value)
  (let ((named (%eval-loop-get state :named)))
    (if named (list 'return-from (car named) value) (list 'return value))))

(defun %eval-loop-bind (state variable init)
  (%eval-loop-add state :bindings (list variable init)))

;; What a binding of the expansion's let* is: a hidden variable is assigned in its place.
(defun %eval-loop-let-binding (binding)
  (if (%eval-loop-temp-p (car binding))
      (list '%eval-ignored (%eval-loop-set (car binding) (cadr binding)))
      binding))

;; ---- the clauses ----

(defun %eval-loop-parse (state)
  (while (%eval-loop-get state :tokens)
    (let ((word (%eval-loop-peek state)))
      (if (null word)
          (error "loop: expected a clause keyword, got: ~S"
                 (car (%eval-loop-get state :tokens))))
      (%eval-loop-skip state)
      (%eval-loop-clause state word))))

(defun %eval-loop-accumulation-p (word)
  (%eval-loop-word-in word
                      '("COLLECT" "COLLECTING" "APPEND" "APPENDING" "NCONC"
                        "NCONCING" "SUM" "SUMMING" "COUNT" "COUNTING" "MAXIMIZE"
                        "MAXIMIZING" "MINIMIZE" "MINIMIZING")))

(defun %eval-loop-clause (state word)
  (cond ((%eval-loop-word-in word '("FOR" "AS")) (%eval-loop-for state))
        ((string= word "WITH") (%eval-loop-with state))
        ((string= word "REPEAT") (%eval-loop-repeat state))
        ((string= word "WHILE")
         (%eval-loop-termination-test state (%eval-loop-next state) nil))
        ((string= word "UNTIL")
         (%eval-loop-termination-test state (%eval-loop-next state) t))
        ((string= word "THEREIS") (%eval-loop-thereis state))
        ((string= word "ALWAYS") (%eval-loop-always state nil))
        ((string= word "NEVER") (%eval-loop-always state t))
        ((string= word "INITIALLY")
         (%eval-loop-add-all state :initially (%eval-loop-forms state)))
        ((string= word "FINALLY")
         (%eval-loop-add-all state :finally (%eval-loop-forms state)))
        ((%eval-loop-word-in word '("DO" "DOING"))
         (%eval-loop-add-all state :body (%eval-loop-forms state)))
        ((string= word "RETURN")
         (%eval-loop-add state
                         :body (%eval-loop-exit state (%eval-loop-next state))))
        ((%eval-loop-word-in word '("WHEN" "IF"))
         (%eval-loop-add state :body (%eval-loop-conditional state nil)))
        ((string= word "UNLESS")
         (%eval-loop-add state :body (%eval-loop-conditional state t)))
        ((%eval-loop-accumulation-p word)
         (%eval-loop-add state :body (%eval-loop-accumulation state word)))
        ((string= word "NAMED")
         (%eval-loop-put state :named (list (%eval-loop-next state))))
        (t (error "loop: unsupported clause: ~A" word))))

(defun %eval-loop-for (state)
  (let ((group nil) (more t))
    (while more
      (setq group (append group (list (%eval-loop-for-piece state))))
      (if (%eval-loop-peek-is state '("AND"))
          (progn
            (%eval-loop-skip state)
            (if (%eval-loop-peek-is state '("FOR" "AS"))
                (%eval-loop-skip state)))
          (setq more nil)))
    (%eval-loop-flush-group state group)))

(defun %eval-loop-for-piece (state)
  (let ((variable (%eval-loop-next state)) (piece (%eval-loop-piece)))
    (if (null variable) (setq variable (%eval-loop-temp state)))
    (if (not (or (symbolp variable) (consp variable)))
        (error "loop: for expects a variable or destructuring pattern, got: ~S"
               variable))
    (%eval-loop-skip-type state)
    (let ((sub (%eval-loop-peek state)))
      (if (null sub) (error "loop: incomplete for clause for ~S" variable))
      (cond ((%eval-loop-word-in sub '("IN" "ON"))
             (%eval-loop-for-list state piece variable sub))
            ((string= sub "=") (%eval-loop-for-equals state piece variable))
            ((%eval-loop-word-in sub
                                 '("FROM" "UPFROM" "DOWNFROM" "TO" "UPTO"
                                   "BELOW" "DOWNTO" "ABOVE" "BY"))
             (%eval-loop-for-numeric state piece
              (%eval-loop-require-variable variable "numeric stepping")))
            ((string= sub "ACROSS")
             (%eval-loop-for-across state piece
              (%eval-loop-require-variable variable "across")))
            ((string= sub "BEING")
             (%eval-loop-for-being state piece
              (%eval-loop-require-variable variable "being")))
            (t (error "loop: unsupported for clause: ~A" sub))))
    piece))

(defun %eval-loop-require-variable (variable what)
  (if (%eval-loop-variable-p variable)
      variable
      (error "loop: ~A does not support destructuring: ~S" what variable)))

;; Joins the and-separated clauses of one for: a lone clause binds and steps in order; a
;; group evaluates every init before binding a variable of its own and steps against the
;; previous iteration's values.
(defun %eval-loop-flush-group (state group)
  (let ((parallel (and (cdr group) t))
        (deferred nil)
        (group-steps nil)
        (group-ends nil)
        (group-prefix nil)
        (drivers nil)
        (head-forms nil))
    (dolist (piece group)
      (dolist (binding (%eval-loop-get piece :binds))
        (if (and parallel (caddr binding))
            (if (null (cadr binding))
                (setq deferred (append deferred (list binding)))
                (let ((temp (%eval-loop-temp state)))
                  (%eval-loop-bind state temp (cadr binding))
                  (setq deferred
                        (append deferred (list (list (car binding) temp t))))))
            (%eval-loop-bind state (car binding) (cadr binding))))
      (setq group-ends (append group-ends (%eval-loop-get piece :ends)))
      (setq group-steps (append group-steps (%eval-loop-get piece :steps)))
      (setq group-prefix (append group-prefix (%eval-loop-get piece :prefix))))
    (dolist (binding deferred)
      (%eval-loop-bind state (car binding) (cadr binding)))
    (if (and parallel group-prefix)
        (progn
          (dolist (record group-prefix)
            (%eval-loop-replace-binding state (car record) (cadr record))
            (setq group-steps
                  (append group-steps
                   (list (list (car record) (%eval-loop-record-step record))))))
          (setq group-prefix nil)))
    (dolist (piece group)
      (if (null (%eval-loop-get piece :prefix))
          (dolist (binding (%eval-loop-get piece :binds))
            (if (caddr binding) (setq drivers (cons (car binding) drivers))))))
    (%eval-loop-add state :group-drivers drivers)
    (if (and (not parallel) group-prefix (null group-ends))
        (%eval-loop-add state
                        :equals-groups (list
                                        (length (%eval-loop-get state :heads))
                                        (length (%eval-loop-get state :steps))
                                        group-prefix
                                        (%eval-loop-get state :driver-ends)
                                        (- (length
                                            (%eval-loop-get state
                                                            :group-drivers))
                                           1))))
    (dolist (piece group)
      (setq head-forms (append head-forms (%eval-loop-get piece :syncs))))
    (setq head-forms
          (append head-forms (%eval-loop-prefix-forms state group-prefix)))
    (%eval-loop-add state
                    :heads (cond ((null group-ends)
                                  (if head-forms
                                      (%eval-loop-progn-t head-forms)
                                      t))
                                 ((null head-forms)
                                  (list 'not (%eval-loop-or group-ends)))
                                 (t
                                  (list 'if
                                        (list 'not (%eval-loop-or group-ends))
                                        (%eval-loop-progn-t head-forms) nil))))
    (if parallel
        (progn
          (%eval-loop-add-all state
                              :steps (%eval-loop-parallel-steps group-steps))
          (dolist (piece group)
            (%eval-loop-add-all state
                                :steps (%eval-loop-get piece :post-steps))))
        (dolist (piece group)
          (dolist (step (%eval-loop-get piece :steps))
            (%eval-loop-add state
                            :steps (%eval-loop-set (car step) (cadr step))))
          (%eval-loop-add-all state :steps (%eval-loop-get piece :post-steps))))
    (dolist (piece group)
      (%eval-loop-add-all state :driver-ends (%eval-loop-get piece :ends)))))

(defun %eval-loop-parallel-steps (steps)
  (cond ((null steps) nil)
        ((null (cdr steps))
         (list (%eval-loop-set (car (car steps)) (cadr (car steps)))))
        (t (list
            (list 'let
                  (list
                   (list '%eval-steps
                    (cons 'list (mapcar (lambda (step) (cadr step)) steps))))
                  (cons 'setf
                        (%eval-nth-assignments
                         (mapcar (lambda (step) (car step)) steps) 0)))))))

(defun %eval-loop-replace-binding (state variable init)
  (let ((binding (assoc variable (%eval-loop-get state :bindings))))
    (if binding
        (rplaca (cdr binding) init)
        (%eval-loop-bind state variable init))))

;; A record of for VAR = INIT [then STEP] is (var init then), then a list of its form.
(defun %eval-loop-record-step (record)
  (if (caddr record) (car (caddr record)) (cadr record)))

(defun %eval-loop-first-flag (state)
  (or (%eval-loop-get state :first)
      (%eval-loop-put state :first (%eval-loop-temp state))))

(defun %eval-loop-prefix-forms (state records)
  (mapcar (lambda (record)
            (%eval-loop-set (car record)
                            (if (caddr record)
                                (list 'if (%eval-loop-first-flag state)
                                      (cadr record) (car (caddr record)))
                                (cadr record)))) records))

;; A literal stays in place; anything else evaluates once, as the clause is read.
(defun %eval-loop-once (state piece form)
  (if (numberp form)
      form
      (let ((temp (%eval-loop-temp state)))
        (%eval-loop-add piece :binds (list temp form nil))
        temp)))

(defun %eval-loop-for-numeric (state piece variable)
  (let ((from-seen nil)
        (limit-word nil)
        (limit nil)
        (by-seen nil)
        (by 1)
        (down nil)
        (more t))
    (while more
      (let ((word (%eval-loop-peek state)))
        (cond ((%eval-loop-word-in word '("FROM" "UPFROM" "DOWNFROM"))
               (if from-seen (error "loop: duplicate from clause"))
               (setq from-seen t)
               (if (string= word "DOWNFROM") (setq down t))
               (%eval-loop-skip state)
               (%eval-loop-add piece
                :binds (list variable (%eval-loop-next state) t)))
              ((%eval-loop-word-in word '("TO" "UPTO" "BELOW" "DOWNTO" "ABOVE"))
               (if limit-word (error "loop: duplicate limit clause"))
               (setq limit-word word)
               (%eval-loop-skip state)
               (setq limit
                     (%eval-loop-once state piece (%eval-loop-next state)))
               (if (%eval-loop-word-in word '("DOWNTO" "ABOVE")) (setq down t)))
              ((%eval-loop-word-in word '("BY"))
               (if by-seen (error "loop: duplicate by clause"))
               (setq by-seen t)
               (%eval-loop-skip state)
               (setq by (%eval-loop-once state piece (%eval-loop-next state))))
              (t (setq more nil)))))
    (if (not from-seen)
        (%eval-loop-put piece
         :binds (cons (list variable 0 t) (%eval-loop-get piece :binds))))
    (%eval-loop-add piece
                    :steps (list variable (list (if down '- '+) variable by)))
    (if limit-word
        (let ((exclusive (%eval-loop-word-in limit-word '("BELOW" "ABOVE"))))
          (%eval-loop-add piece
                          :ends (list (if down
                                          (if exclusive '<= '<)
                                          (if exclusive '>= '>)) variable
                                      limit))))))

(defun %eval-loop-step-form (cursor by)
  (if by (list 'funcall by cursor) (list 'cdr cursor)))

;; 'name as a step function is #'name, the local one included.
(defun %eval-loop-function-form (form)
  (if (and (consp form) (eq (car form) 'quote) (symbolp (cadr form)))
      (list 'function (cadr form))
      form))

(defun %eval-loop-check-list (list) (%check-list list 'endp))

(defun %eval-loop-for-list (state piece pattern sub)
  (%eval-loop-skip state)
  (let ((list-form (%eval-loop-next state)) (by nil) (by-form nil))
    (if (%eval-loop-peek-is state '("BY"))
        (progn
          (%eval-loop-skip state)
          (setq by-form (%eval-loop-function-form (%eval-loop-next state)))
          (setq by (if (symbolp by-form) by-form (%eval-loop-temp state)))))
    (if (string= sub "ON")
        (if (%eval-loop-variable-p pattern)
            (progn
              (%eval-loop-add piece :binds (list pattern list-form t))
              (%eval-loop-add piece :ends (list 'not (list 'consp pattern)))
              (%eval-loop-add piece
               :steps (list pattern (%eval-loop-step-form pattern by))))
            (let ((tail (%eval-loop-temp state)))
              (%eval-loop-add piece :binds (list tail list-form nil))
              (%eval-loop-add piece :ends (list 'not (list 'consp tail)))
              (%eval-loop-destructure-into piece pattern tail)
              (%eval-loop-add piece
               :steps (list tail (%eval-loop-step-form tail by)))))
        (let ((cursor (%eval-loop-temp state)))
          (%eval-loop-add piece
           :binds (list cursor (list '%eval-loop-check-list list-form) nil))
          (%eval-loop-add piece
           :ends (list 'if (list 'consp cursor) nil (list 'endp cursor)))
          (%eval-loop-destructure-into piece pattern (list 'car cursor))
          (%eval-loop-add piece
           :steps (list cursor (%eval-loop-step-form cursor by)))))
    (if (and by (not (symbolp by-form)))
        (%eval-loop-add piece :binds (list by by-form nil)))))

(defun %eval-loop-destructure-pairs (pattern source)
  (cond ((null pattern) nil)
        ((%eval-loop-variable-p pattern) (list (list pattern source)))
        ((consp pattern)
         (append (%eval-loop-destructure-pairs (car pattern) (list 'car source))
          (%eval-loop-destructure-pairs (cdr pattern) (list 'cdr source))))
        (t (error "loop: invalid destructuring pattern: ~S" pattern))))

(defun %eval-loop-destructure-into (piece pattern source)
  (dolist (pair (%eval-loop-destructure-pairs pattern source))
    (%eval-loop-add piece :binds (list (car pair) (cadr pair) t))
    (%eval-loop-add piece :syncs (%eval-loop-set (car pair) (cadr pair)))))

(defun %eval-loop-for-across (state piece variable)
  (%eval-loop-skip state)
  (let* ((form (%eval-loop-next state))
         (sequence (%eval-loop-temp state))
         (index (%eval-loop-temp state))
         (element
          (list 'if (list '< index (list 'length sequence))
                (list 'elt sequence index) nil)))
    (%eval-loop-add piece :binds (list sequence form nil))
    (%eval-loop-add piece :binds (list index 0 nil))
    (%eval-loop-add piece :binds (list variable element t))
    (%eval-loop-add piece :ends (list '>= index (list 'length sequence)))
    (%eval-loop-add piece :steps (list index (list '+ index 1)))
    (%eval-loop-add piece :syncs (%eval-loop-set variable element))))

(defun %eval-loop-for-being (state piece variable)
  (%eval-loop-skip state)
  (if (%eval-loop-word-in
       (%eval-loop-plain-name (car (%eval-loop-get state :tokens)))
       '("THE" "EACH"))
      (%eval-loop-skip state))
  (let ((kind (%eval-loop-plain-name (%eval-loop-next state))))
    (if (not
         (%eval-loop-word-in (%eval-loop-plain-name (%eval-loop-next state))
                             '("OF" "IN")))
        (error "loop: a being clause expects of or in"))
    (cond ((%eval-loop-word-in kind '("HASH-KEY" "HASH-KEYS"))
           (%eval-loop-for-being-hash state piece variable t))
          ((%eval-loop-word-in kind '("HASH-VALUE" "HASH-VALUES"))
           (%eval-loop-for-being-hash state piece variable nil))
          ((%eval-loop-word-in kind
                               '("SYMBOL" "SYMBOLS" "EXTERNAL-SYMBOL"
                                 "EXTERNAL-SYMBOLS" "PRESENT-SYMBOL"
                                 "PRESENT-SYMBOLS"))
           (%eval-loop-walk-cursor state piece variable
            (%eval-soft-loop-symbols kind (%eval-loop-next state))
            (lambda (cursor) (list 'car cursor))))
          (t (error "loop: unsupported being clause: ~A" kind)))))

;; What a package walk snapshots. Its rows come along only for a program spelling the word
;; (EvalMacroRows.loopWordRow), since they reach the whole package table.
(defun %eval-soft-loop-symbols (kind package)
  (cond ((%eval-loop-word-in kind '("EXTERNAL-SYMBOL" "EXTERNAL-SYMBOLS"))
         (list '%eval-external-symbols-of package))
        ((%eval-loop-word-in kind '("PRESENT-SYMBOL" "PRESENT-SYMBOLS"))
         (list 'mapcar '(function car)
          (list '%eval-package-entries package '(quote (:internal :external)))))
        (t (list '%eval-symbols-of package))))

(defun %eval-loop-for-being-hash (state piece variable keys)
  (let ((table (%eval-loop-next state)) (using nil) (using-key nil))
    (if (%eval-loop-word-in
         (%eval-loop-plain-name (car (%eval-loop-get state :tokens)))
         '("USING"))
        (let ((spec
               (progn
                 (%eval-loop-skip state)
                 (%eval-loop-next state))))
          (setq using-key
                (%eval-loop-word-in (%eval-loop-plain-name (car spec))
                                    '("HASH-KEY" "HASH-KEYS")))
          (setq using (cadr spec))))
    (let ((cursor
           (%eval-loop-walk-cursor state piece variable
            (%eval-loop-hash-pairs table)
            (lambda (cursor) (%eval-loop-hash-part cursor keys)))))
      (if using
          (progn
            (%eval-loop-add piece
             :binds (list using (%eval-loop-hash-part cursor using-key) t))
            (%eval-loop-add piece
                            :post-steps (%eval-loop-set using
                                                        (%eval-loop-hash-part
                                                         cursor
                                                         using-key))))))))

(defun %eval-loop-hash-part (cursor key)
  (list (if key 'car 'cdr) (list 'car cursor)))

(defun %eval-loop-hash-pairs (table)
  `(let ((%eval-entries nil))
     (maphash (lambda (%eval-key %eval-value)
                (setq %eval-entries
                      (cons (cons %eval-key %eval-value) %eval-entries)))
              ,table)
     %eval-entries))

;; A walk over a snapshot: the variable keeps what the exhausted cursor holds, nil.
(defun %eval-loop-walk-cursor (state piece variable universe read)
  (let ((cursor (%eval-loop-temp state)))
    (%eval-loop-add piece :binds (list cursor universe nil))
    (%eval-loop-add piece :ends (list 'not (list 'consp cursor)))
    (%eval-loop-add piece :binds (list variable (funcall read cursor) t))
    (%eval-loop-add piece :steps (list cursor (list 'cdr cursor)))
    (%eval-loop-add piece
                    :post-steps (%eval-loop-set variable (funcall read cursor)))
    cursor))

(defun %eval-loop-for-equals (state piece pattern)
  (%eval-loop-skip state)
  (let* ((init (%eval-loop-next state))
         (then
          (if (%eval-loop-peek-is state '("THEN"))
              (progn
                (%eval-loop-skip state)
                (list (%eval-loop-next state)))
              nil)))
    (if (%eval-loop-variable-p pattern)
        (progn
          (%eval-loop-add piece :binds (list pattern nil t))
          (%eval-loop-add piece :prefix (list pattern init then)))
        (let ((whole (%eval-loop-temp state)))
          (%eval-loop-add piece :binds (list whole nil nil))
          (%eval-loop-add piece :prefix (list whole init then))
          (dolist (pair (%eval-loop-destructure-pairs pattern whole))
            (%eval-loop-add piece :binds (list (car pair) nil t))
            (%eval-loop-add piece
                            :prefix (list (car pair) (cadr pair) nil)))))))

(defun %eval-loop-with (state)
  (let ((group nil) (more t))
    (while more
      (let ((variable (%eval-loop-next state)) (value nil))
        (if (null variable) (setq variable (%eval-loop-temp state)))
        (if (not (or (symbolp variable) (consp variable)))
            (error "loop: with expects a variable, got: ~S" variable))
        (%eval-loop-skip-type state)
        (if (%eval-loop-peek-is state '("="))
            (progn
              (%eval-loop-skip state)
              (setq value (%eval-loop-next state))))
        (setq group (append group (list (list variable value))))
        (if (%eval-loop-peek-is state '("AND"))
            (%eval-loop-skip state)
            (setq more nil))))
    (if (cdr group)
        (let ((deferred nil))
          (dolist (entry group)
            (if (null (cadr entry))
                (setq deferred (append deferred (list entry)))
                (let ((temp (%eval-loop-temp state)))
                  (%eval-loop-bind state temp (cadr entry))
                  (setq deferred
                        (append deferred (list (list (car entry) temp)))))))
          (dolist (entry deferred)
            (%eval-loop-bind-pattern state (car entry) (cadr entry))))
        (%eval-loop-bind-pattern state (car (car group)) (cadr (car group))))))

(defun %eval-loop-bind-pattern (state pattern init)
  (if (%eval-loop-variable-p pattern)
      (%eval-loop-bind state pattern init)
      (let ((source init))
        (if (not (or (symbolp init) (%eval-loop-temp-p init)))
            (progn
              (setq source (%eval-loop-temp state))
              (%eval-loop-bind state source init)))
        (dolist (pair (%eval-loop-destructure-pairs pattern source))
          (%eval-loop-bind state (car pair) (cadr pair))))))

;; Before any body clause the test is a head at its own position; after one it runs where
;; it stands and leaves through the epilogue.
(defun %eval-loop-termination-test (state test until)
  (if (%eval-loop-get state :body)
      (%eval-loop-add state
                      :body (if until
                                (list 'if test '%eval-loop-epilogue nil)
                                (list 'if test nil '%eval-loop-epilogue)))
      (%eval-loop-add state :heads (if until (list 'not test) test))))

(defun %eval-loop-set-termination (state value)
  (if (%eval-loop-get state :accumulator)
      (error
       "loop: cannot combine always/never/thereis with accumulation (use into)"))
  (%eval-loop-put state
                  :termination (if (or value
                                       (eq (%eval-loop-get state :termination)
                                           :true))
                                   :true :false)))

(defun %eval-loop-always (state never)
  (%eval-loop-set-termination state t)
  (let ((form (%eval-loop-next state)) (exit (%eval-loop-exit state nil)))
    (%eval-loop-add state
     :body (if never (list 'if form exit nil) (list 'if form nil exit)))))

(defun %eval-loop-thereis (state)
  (%eval-loop-set-termination state nil)
  (let ((value (%eval-loop-temp state)))
    (%eval-loop-add state
                    :body (list 'progn
                                (%eval-loop-set value (%eval-loop-next state))
                                (list 'if value (%eval-loop-exit state value)
                                      nil)))))

(defun %eval-loop-repeat (state)
  (let* ((form (%eval-loop-next state))
         (counter (%eval-loop-temp state))
         (test (list '<= counter 0)))
    (%eval-loop-bind state counter form)
    (%eval-loop-add state :heads (list 'not test))
    (%eval-loop-add state :steps (%eval-loop-set counter (list '- counter 1)))
    (%eval-loop-add state :driver-ends test)))

;; A branch naming the anaphoric it reads the test's value from a place of its own.
(defun %eval-loop-conditional (state negate)
  (let* ((test (%eval-loop-next state))
         (then (%eval-loop-branch state))
         (else
          (if (%eval-loop-peek-is state '("ELSE"))
              (progn
                (%eval-loop-skip state)
                (%eval-loop-branch state))
              nil))
         (it (%eval-loop-temp state))
         (then-form (%eval-soft-loop-substitute-it then it))
         (else-form (%eval-soft-loop-substitute-it else it)))
    (if (%eval-loop-peek-is state '("END")) (%eval-loop-skip state))
    (if (and (eq then-form then) (eq else-form else))
        (list 'if (if negate (list 'not test) test) then else)
        (list 'progn (%eval-loop-set it test)
              (list 'if (if negate (list 'not it) it) then-form else-form)))))

(defun %eval-loop-branch (state)
  (let ((forms (list (%eval-loop-selectable state))))
    (while (%eval-loop-peek-is state '("AND"))
      (%eval-loop-skip state)
      (setq forms (append forms (list (%eval-loop-selectable state)))))
    (if (cdr forms) (cons 'progn forms) (car forms))))

(defun %eval-loop-selectable (state)
  (let ((word (%eval-loop-peek state)))
    (if (null word)
        (error "loop: expected a clause after when or unless, got: ~S"
               (car (%eval-loop-get state :tokens))))
    (%eval-loop-skip state)
    (cond
     ((%eval-loop-word-in word '("DO" "DOING"))
      (cons 'progn (%eval-loop-forms state)))
     ((string= word "RETURN") (%eval-loop-exit state (%eval-loop-next state)))
     ((%eval-loop-word-in word '("WHEN" "IF"))
      (%eval-loop-conditional state nil))
     ((string= word "UNLESS") (%eval-loop-conditional state t))
     ((%eval-loop-accumulation-p word) (%eval-loop-accumulation state word))
     (t (error "loop: clause not selectable by when or unless: ~A" word)))))

(defun %eval-loop-kind (word)
  (cond ((%eval-loop-word-in word '("COLLECT" "COLLECTING")) :collect)
        ((%eval-loop-word-in word '("APPEND" "APPENDING")) :append)
        ((%eval-loop-word-in word '("NCONC" "NCONCING")) :nconc)
        ((%eval-loop-word-in word '("SUM" "SUMMING")) :sum)
        ((%eval-loop-word-in word '("COUNT" "COUNTING")) :count)
        ((%eval-loop-word-in word '("MAXIMIZE" "MAXIMIZING")) :maximize)
        (t :minimize)))

(defun %eval-loop-accumulation (state word)
  (let* ((kind (%eval-loop-kind word))
         (init (if (member kind '(:sum :count)) 0 nil))
         (reverse (and (member kind '(:collect :append :nconc)) t))
         (value (%eval-loop-next state))
         (target nil)
         (tail nil))
    (%eval-loop-skip-type state)
    (if (%eval-loop-peek-is state '("INTO"))
        (progn
          (%eval-loop-skip state)
          (setq target (%eval-loop-next state))
          (if (not (symbolp target))
              (error "loop: into expects a variable, got: ~S" target))
          (setq tail (%eval-loop-into-tail state target init reverse)))
        (setq target (%eval-loop-implicit state init reverse)))
    (%eval-loop-skip-type state)
    (%eval-loop-accumulate kind target tail value)))

;; An into variable is visible mid-loop, so a list accumulating into one grows in order
;; through a tail cursor. Its entry in :into is (variable reverse tail).
(defun %eval-loop-into-tail (state variable init reverse)
  (let ((entry (assoc variable (%eval-loop-get state :into))))
    (cond
     ((null entry)
      (let ((tail (if reverse (%eval-loop-temp state) nil)))
        (%eval-loop-add state :into (list variable reverse tail))
        (%eval-loop-bind state variable init)
        tail))
     ((eq (cadr entry) reverse) (caddr entry))
     (t (error "loop: incompatible accumulation kinds into ~A" variable)))))

(defun %eval-loop-implicit (state init reverse)
  (if (%eval-loop-get state :termination)
      (error
       "loop: cannot combine always/never/thereis with accumulation (use into)"))
  (let ((accumulator (%eval-loop-get state :accumulator)))
    (cond ((null accumulator)
           (setq accumulator (%eval-loop-temp state))
           (%eval-loop-put state :accumulator accumulator)
           (%eval-loop-put state :accumulator-init init)
           (%eval-loop-put state :accumulator-reverse reverse)
           (%eval-loop-bind state accumulator init)
           accumulator)
          ((and (eq (%eval-loop-get state :accumulator-reverse) reverse)
                (eql (%eval-loop-get state :accumulator-init) init))
           accumulator)
          (t (error
              "loop: cannot mix accumulation kinds without into (use distinct into variables)")))))

(defun %eval-loop-accumulate (kind target tail value)
  (cond ((eq kind :collect)
         (if tail
             (%eval-loop-attach target tail (list 'cons value nil))
             (%eval-loop-set target (list 'cons value target))))
        ((eq kind :append)
         (if tail
             (%eval-loop-attach target tail (list 'copy-list value))
             (%eval-loop-set target (list 'revappend value target))))
        ((eq kind :nconc)
         (if tail
             (%eval-loop-attach target tail value)
             (%eval-loop-set target (list 'nreconc value target))))
        ((eq kind :sum) (%eval-loop-set target (list '+ target value)))
        ((eq kind :count)
         (list 'if value (%eval-loop-set target (list '+ target 1)) nil))
        (t (%eval-loop-set target
                           (list 'if target
                                 (list (if (eq kind :maximize) 'max 'min) target
                                       value) value)))))

(defun %eval-loop-attach (target tail elements)
  `(let ((%eval-elements ,elements))
     (if ,target (rplacd ,tail %eval-elements) (setf ,target %eval-elements))
     (if %eval-elements (setf ,tail (last %eval-elements)) nil)))

(defun %eval-loop-result (state)
  (let ((accumulator (%eval-loop-get state :accumulator)))
    (cond ((null accumulator) (eq (%eval-loop-get state :termination) :true))
     ((%eval-loop-get state :accumulator-reverse) (list 'nreverse accumulator))
     (t accumulator))))

;; ---- what needs the whole clause list ----

;; A sequential for = whose form names a later clause's driver has to see that driver's
;; previous element, as clauses step in source order: it computes into a place at its own
;; position among the steps, and the head reads the place.
(defun %eval-loop-retarget (state)
  (dolist (group (reverse (%eval-loop-get state :equals-groups)))
    (let ((later (%eval-loop-later-drivers state (nth 4 group)))
          (records (nth 2 group)))
      (if (and later (%eval-loop-some-record-names records later))
          (let ((temps nil) (sets nil) (via nil))
            (dolist (record records)
              (let ((temp (%eval-loop-temp state)))
                (setq sets
                      (append sets
                              (list
                               (%eval-loop-set temp
                                               (%eval-loop-substitute-symbols
                                                (%eval-loop-record-step record)
                                                temps)))))
                (setq via
                      (append via
                       (list (list (car record) (cadr record) (list temp)))))
                (setq temps (cons (cons (car record) temp) temps))))
            (let ((step (if (cdr sets) (cons 'progn sets) (car sets))))
              (%eval-loop-put state
                              :steps (%eval-loop-insert
                                      (%eval-loop-get state :steps)
                                      (nth 1 group)
                                      (if (nth 3 group)
                                          (list 'if
                                                (list 'not
                                                 (%eval-loop-or (nth 3 group)))
                                                step nil)
                                          step)))
              (rplaca (nthcdr (nth 0 group) (%eval-loop-get state :heads))
               (%eval-loop-progn-t (%eval-loop-prefix-forms state via)))))))))

(defun %eval-loop-later-drivers (state index)
  (let ((later nil))
    (dolist (drivers (nthcdr (+ index 1) (%eval-loop-get state :group-drivers)))
      (setq later (append drivers later)))
    later))

(defun %eval-loop-some-record-names (records names)
  (and records
       (or (%eval-loop-references (%eval-loop-record-step (car records)) names)
           (%eval-loop-some-record-names (cdr records) names))))

(defun %eval-loop-references (form names)
  (cond ((symbolp form) (and form (member form names) t))
        ((not (consp form)) nil)
        ((eq (car form) 'quote) nil)
        (t (or (%eval-loop-references (car form) names)
               (%eval-loop-references (cdr form) names)))))

(defun %eval-loop-substitute-symbols (form replacements)
  (cond ((and form (symbolp form))
         (let ((replacement (assoc form replacements)))
           (if replacement (cdr replacement) form)))
        ((not (consp form)) form)
        ((eq (car form) 'quote) form)
        (t (cons (%eval-loop-substitute-symbols (car form) replacements)
                 (%eval-loop-substitute-symbols (cdr form) replacements)))))

(defun %eval-loop-insert (forms position form)
  (if (= position 0)
      (cons form forms)
      (cons (car forms) (%eval-loop-insert (cdr forms) (- position 1) form))))

;; TREE with what REPLACE answers for a node, itself when nothing changed. A list headed by
;; one of HEADS is left alone. Soft: the names of its callers' lists are data.
(defun %eval-soft-loop-substitute (tree heads replace)
  (let ((replaced (funcall replace tree)))
    (cond (replaced replaced)
          ((not (consp tree)) tree)
          ((member (car tree) heads) tree)
          (t (let ((head (%eval-soft-loop-substitute (car tree) heads replace))
                   (tail (%eval-soft-loop-substitute (cdr tree) heads replace)))
               (if (and (eq head (car tree)) (eq tail (cdr tree)))
                   tree
                   (cons head tail)))))))

(defun %eval-soft-loop-substitute-it (tree it)
  (%eval-soft-loop-substitute tree '(quote function loop)
                              (lambda (node)
                                (if (and node (symbolp node)
                                         (not (keywordp node))
                                         (string= (symbol-name node) "IT"))
                                    it
                                    nil))))

;; The epilogue's exit for its marker and for (loop-finish). It is a return, so it stands
;; only directly inside this loop's block.
(defun %eval-soft-loop-substitute-exits (forms exit)
  (mapcar (lambda (form)
            (%eval-soft-loop-substitute form
             '(quote function lambda defun loop do do* dolist dotimes)
             (lambda (node)
               (if (or (eq node '%eval-loop-epilogue)
                       (and (consp node) (symbolp (car node)) (car node)
                            (string= (symbol-name (car node)) "LOOP-FINISH")))
                   exit
                   nil)))) forms))

(defun %eval-loop-expansion (tokens)
  (let ((state (%eval-loop-state tokens)))
    (%eval-loop-parse state)
    (%eval-loop-retarget state)
    (let* ((result (%eval-loop-result state))
           (exit
            (cons 'progn
                  (append (%eval-loop-get state :finally)
                          (list (%eval-loop-exit state result)))))
           (heads
            (%eval-soft-loop-substitute-exits (%eval-loop-get state :heads)
                                              exit))
           (first (%eval-loop-get state :first))
           (initially
            (%eval-soft-loop-substitute-exits (%eval-loop-get state :initially)
                                              exit))
           (body
            (%eval-soft-loop-substitute-exits (%eval-loop-get state :body)
                                              exit))
           (steps
            (%eval-soft-loop-substitute-exits (%eval-loop-get state :steps)
                                              exit))
           (bindings (%eval-loop-get state :bindings))
           (test
            (cond ((null heads) t)
                  ((cdr heads) (cons 'and heads))
                  (t (car heads))))
           (run
            (append (list 'while test) body steps
                    (if first (list (%eval-loop-set first nil)) nil)))
           (form
            `(let* ((%eval-loop (make-list ,(%eval-loop-get state :count)))
                    ,@(mapcar
                       #'%eval-loop-let-binding
                       (if first (cons (list first t) bindings) bindings)))
               ,@initially
               ,(if (or body steps first) run (append run (list nil)))
               ,@(%eval-loop-get state :finally)
               ,result))
           (named (%eval-loop-get state :named)))
      (list 'block (if named (car named) nil) form))))

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

;; A local function hides a local macro of its name; _eval asks for the macro first.
(defun %eval-unshadowed-macros (definitions env)
  (let ((binding (assoc '%eval-macros env)))
    (if binding
        `((%eval-macros
           ',(remove-if (lambda (entry) (assoc (car entry) definitions))
                        (cdr binding))))
        nil)))

(defun %eval-row-flet (args env)
  `(let ((%eval-functions
          (append (list
                   ,@(mapcar (lambda (definition)
                               `(cons ',(car definition)
                                      ,(%eval-local-function definition)))
                             (car args))) ',(%eval-functions-of env)))
         ,@(%eval-unshadowed-macros (car args) env))
     ,@(cdr args)))

(defun %eval-row-labels (args env)
  `(let ((%eval-functions
          (append ',(mapcar (lambda (definition) (cons (car definition) nil))
                            (car args)) ',(%eval-functions-of env)))
         ,@(%eval-unshadowed-macros (car args) env))
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

;; ---- macros bound by a form ----

;; The alist _eval's operator path reads before the local functions: (name . expander), an
;; expander taking the whole form. Its body runs where the macrolet stands.
(defun %eval-row-macrolet (args env)
  `(let ((%eval-macros
          (append (list
                   ,@(mapcar (lambda (definition)
                               `(cons ',(car definition)
                                      ,(%eval-macro-function definition)))
                             (car args))) ',(cdr (assoc '%eval-macros env)))))
     ,@(cdr args)))

(defun %eval-macro-function (definition)
  (let* ((lambda-list (cadr definition))
         (whole (eq (car lambda-list) '&whole))
         (parameters (if whole (cddr lambda-list) lambda-list))
         (body (cddr definition)))
    `(lambda (%eval-macro-form)
       (let (,@(if whole `((,(cadr lambda-list) %eval-macro-form)) nil)
             ,@(%eval-macro-environment parameters))
         (destructuring-bind ,(%eval-macro-parameters parameters)
             (cdr %eval-macro-form)
           ,(%eval-soft-block (car definition) (cons 'progn body) body))))))

;; &environment is an empty environment: what a form reads of one, it reads from env.
(defun %eval-macro-environment (parameters)
  (cond ((atom parameters) nil)
   ((eq (car parameters) '&environment) (list (list (cadr parameters) nil)))
   (t (%eval-macro-environment (cdr parameters)))))

(defun %eval-macro-parameters (parameters)
  (cond ((atom parameters) parameters)
        ((eq (car parameters) '&environment) (cddr parameters))
        (t (cons (car parameters) (%eval-macro-parameters (cdr parameters))))))

;; A symbol macro is an ordinary binding whose value carries the mark: a let of its name
;; shadows it as any variable, and _eval and _store act on the expansion where they find
;; one.
(defun %eval-row-symbol-macrolet (args env)
  `(let ,(mapcar
          (lambda
           (binding)
           (list (car binding)
                 (list 'quote (cons '%eval-symbol-macro (cadr binding)))))
          (car args))
     ,@(cdr args)))

;; The alist of the global symbol macros, read through the mirror where no variable answers a
;; name: _eval and _store run for a global's first mirrored store, before this defvar.
(defvar %eval-symbol-macros% nil)

(defun %eval-row-define-symbol-macro (args env)
  `(%eval-define-symbol-macro ',(car args) ',(cadr args)))

(defun %eval-define-symbol-macro (name expansion)
  (let ((entry (assoc name %eval-symbol-macros%)))
    (if entry
        (rplacd entry expansion)
        (setq %eval-symbol-macros%
              (cons (cons name expansion) %eval-symbol-macros%)))
    name))

;; The instance a with-slots or with-accessors names, evaluated once. Its variables' forms
;; hold the value, not a variable: another such form inside the body rebinds any name.
(defun %eval-instance-form (args)
  `(let ((%eval-instance ,(cadr args)))
     (%eval-instance-macros ,(car args) ,@(cddr args))))

(defun %eval-row-%eval-instance-macros (args env)
  (let ((instance (list 'quote (cdr (assoc '%eval-instance env)))))
    `(symbol-macrolet ,(mapcar
                        (lambda
                         (binding)
                         (list (car binding)
                               (%eval-instance-read binding instance)))
                        (car args))
       ,@(cdr args))))

;; A binding is (variable slot) or (variable accessor); a slot's list says slot-value.
(defun %eval-instance-read (binding instance)
  (if (cddr binding)
      (list 'slot-value instance (list 'quote (cadr binding)))
      (list (cadr binding) instance)))

;; Naming the place row brings it along, for an assignment of a slot's variable.
(defun %eval-row-with-slots (args env)
  (%eval-instance-form
   (cons (mapcar (lambda (slot)
                   (if (consp slot)
                       (list (car slot) (cadr slot) '%eval-place-slot-value)
                       (list slot slot '%eval-place-slot-value))) (car args))
         (cdr args))))

(defun %eval-row-with-accessors (args env) (%eval-instance-form args))

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

;; ---- restarts ----

;; One cluster of records for the extent of THUNK. A record is what the compile-time
;; expansion pushes, its name computed. The empty restart-bind saves and restores the stack
;; on every exit, and is what brings the restart runtime.
(defun %eval-with-restarts (records thunk)
  (restart-bind ()
    (setq %restart-clusters% (cons records %restart-clusters%))
    (funcall thunk)))

(defun %eval-row-restart-case (args env)
  (if (cdr args)
      `(%eval-restart-case (lambda () ,(car args))
                           (list ,@(mapcar #'%eval-restart-clause (cdr args))))
      (car args)))

;; (name function report interactive test), the function over the invocation's arguments.
(defun %eval-restart-clause (clause)
  (let ((rest (cddr clause)) (report nil) (interactive nil) (test nil))
    (while (and (cdr rest) (member (car rest) '(:report :interactive :test)))
      (let ((value (%eval-restart-option (cadr rest))))
        (cond ((eq (car rest) :report) (setq report value))
              ((eq (car rest) :interactive) (setq interactive value))
              (t (setq test value))))
      (setq rest (cddr rest)))
    `(list ',(car clause)
           (lambda (%eval-restart-arguments)
             (let ,(%eval-restart-parameters (cadr clause) 0 nil) ,@rest))
           ,report ,interactive ,test)))

(defun %eval-restart-option (value)
  (if (and value (symbolp value) (not (keywordp value)) (not (eq value t)))
      (list 'function value)
      value))

;; Bound by position as the compile-time expansion does: an &optional parameter takes nil
;; when absent, and a &rest one the tail.
(defun %eval-restart-parameters (parameters k rest)
  (cond ((null parameters) nil)
        ((eq (car parameters) '&optional)
         (%eval-restart-parameters (cdr parameters) k rest))
        ((eq (car parameters) '&rest)
         (%eval-restart-parameters (cdr parameters) k t))
        (t (cons (list (if (consp (car parameters))
                           (car (car parameters))
                           (car parameters))
                       (list (if rest 'nthcdr 'nth) k '%eval-restart-arguments))
                 (%eval-restart-parameters (cdr parameters) (if rest k (+ k 1))
                                           rest)))))

(defun %eval-restart-records (clauses tag k)
  (if clauses
      (let ((clause (car clauses)))
        (cons (list '%restart (car clause)
                    (lambda (arguments) (throw tag (cons k arguments)))
                    (nth 2 clause) (nth 3 clause) (nth 4 clause))
              (%eval-restart-records (cdr clauses) tag (+ k 1))))
      nil))

;; A clause body runs after the throw has unwound to this frame, with the stack restored.
(defun %eval-restart-case (protected clauses)
  (let* ((tag (list '%restart))
         (result
          (catch tag
            (cons -1
                  (%eval-with-restarts (%eval-restart-records clauses tag 0)
                   (lambda () (multiple-value-list (funcall protected))))))))
    (if (eql (car result) -1)
        (values-list (cdr result))
        (funcall (cadr (nth (car result) clauses)) (cdr result)))))

(defun %eval-row-restart-bind (args env)
  `(%eval-with-restarts (list ,@(mapcar #'%eval-restart-binding (car args)))
                        (lambda () ,@(cdr args))))

(defun %eval-restart-binding (binding)
  `(list '%restart ',(car binding) (%eval-restart-invoker ,(cadr binding))
         ,(getf (cddr binding) :report-function) nil nil))

(defun %eval-restart-invoker (function)
  (lambda (arguments) (apply function arguments)))

(defun %eval-row-with-simple-restart (args env)
  (let* ((spec (car args)) (control (cadr spec)))
    `(restart-case (progn ,@(cdr args))
       (,(car spec) ()
         :report ,(if (cddr spec)
                      `(lambda (%eval-stream)
                         (format %eval-stream ,control ,@(cddr spec)))
                      control)
         (values nil t)))))

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

;; The name a local function, as the compile-time expansion binds it.
(defun %eval-row-with-package-iterator (args env)
  (let ((spec (car args)))
    `(let ((%eval-entries (%eval-package-entries ,(cadr spec) ',(cddr spec))))
       (flet ((,(car spec) ()
                (if %eval-entries
                    (let ((%eval-entry (car %eval-entries)))
                      (setq %eval-entries (cdr %eval-entries))
                      (values t (car %eval-entry) (cadr %eval-entry)
                              (caddr %eval-entry)))
                    (values nil nil nil nil))))
         ,@(cdr args)))))

;; (symbol status package) of what a package list holds of the symbol types.
(defun %eval-package-entries (packages types)
  (%package-iterator-entries packages types))

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
