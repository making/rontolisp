;; The COMPILE runtime of a compiled program. Injected ONLY into programs that
;; reference compile without defining it, or that register a precompiled
;; definition (LispMacroExpander gate), so every other program stays
;; byte-identical.
;;
;; A compiled program has no runtime compiler, and three caller shapes reach here.
;;
;; The first is the (funcall (compile nil `(lambda () ,code))) idiom of
;; postmodern's build-dao-methods, whose method construction ALREADY ran at
;; definition time -- the evaluator's compile intercepted it and spliced the
;; folded method definitions into the program as top-level forms ("expand and
;; splice", see MopEvalCapture). When the class-definition protocol re-runs the
;; same call at program start, the work is already in the program, so a
;; definition that defines methods answers a do-nothing function.
;;
;; The second is a definition the program's own definitions compile --
;; contextl's layered-function callers, the method functions of ensure-method.
;; The macro-time evaluator ran that code when the compile paths replayed the
;; definitions, and every definition it compiled was registered before the form
;; that compiled it (CompiledTemplates): a pattern, the definition with a hole
;; for every datum that is no literal and a marker for every uninterned symbol,
;; and a factory, the definition compiled as a closure over the holes. A
;; definition matching a pattern is the factory's function over the run-time
;; hole values: compiled code, closed over the run-time objects.
;;
;; Everything else is EVALUATED (.kb/eval-runtime.md). That is compile's own
;; contract where there is no compiler: CL says (compile nil lambda-expression)
;; answers a function equivalent to the lambda expression closed in the NULL
;; lexical environment, which is exactly what eval of the same form answers --
;; only slower. esrap builds a trampoline this way for a semantic predicate whose
;; symbol it does not own: (compile nil `(lambda ,args (,name ,@args))).
;; Referencing eval here also GATES the eval runtime into the program, so a
;; program that only calls compile still carries the interpreter it needs.

;; Pattern -> factory, the latest registration first. Prepended: the
;; registrations run at their own positions.
(defvar %compiled-templates% nil)

;; The run-time uninterned symbols the pattern being matched bound its numbered
;; gensym markers to: (k . symbol).
(defvar %template-gensyms% nil)

;; The name, or with no name the function, and neither failure value: nothing here
;; warns. With no definition the name's current one is already the answer, so only a
;; name with no definition signals.
(defun compile (name &optional (definition nil definition-p))
  (if definition-p
      (let ((compiled (%compile-definition definition)))
        (if name
            (progn
              (setf (fdefinition name) compiled)
              (values name nil nil))
            (values compiled nil nil)))
      (progn
        (%compile-check-name name)
        (values name nil nil))))

;; A macro's name has a definition. Replaced by CompileRuntime where the program does
;; not carry macro-function: a program that does not name compile (the runtime is
;; here for a coerce, or a precompiled definition) never gets this far, and the
;; reference would only warn.
(defun %compile-check-name (name)
  (if (and (symbolp name) (macro-function name)) nil (fdefinition name)))

(defun %compile-definition (definition)
  (cond ((functionp definition) definition)
        ((not (and (consp definition) (eq (car definition) 'lambda)))
         (error "COMPILE expects a (lambda ...) definition, got ~S" definition))
        ((%compile-defines-methods-p definition) (lambda () nil))
        (t (let ((compiled (%compiled-template definition)))
             (if compiled compiled (eval definition))))))

;; The CDR direction is a LOOP, not a recursion: the definition of a function
;; with a long body is an ordinary flat list of body forms, and one frame per
;; element overflowed the stack. Only the CAR direction recurses, so the depth
;; is the form's nesting depth.
(defun %compile-defines-methods-p (form)
  (let ((p form) (found nil))
    (while (and (consp p) (not found))
      (cond ((eq (car p) 'defmethod) (setq found t))
            ((%compile-defines-methods-p (car p)) (setq found t))
            (t (setq p (cdr p)))))
    found))

(defun %register-compiled-template (pattern factory)
  (setq %compiled-templates% (cons (cons pattern factory) %compiled-templates%))
  nil)

(defun %compiled-template (definition)
  (let ((found nil) (entries %compiled-templates%))
    (while (and entries (not found))
      (setq %template-gensyms% nil)
      (let ((holes (%template-match (car (car entries)) definition nil)))
        (if (eq holes '%template-mismatch%)
            nil
            (setq found (apply (cdr (car entries)) (reverse holes)))))
      (setq entries (cdr entries)))
    found))

;; The hole values PATTERN binds in FORM, consed onto HOLES last first; the
;; symbol %template-mismatch% once anything differs. The cdr direction is a
;; loop, like %compile-defines-methods-p's; it stops at a gensym marker, which
;; stands for one symbol (an element, or a dotted tail).
(defun %template-match (pattern form holes)
  (let ((p pattern) (f form))
    (while (and (consp p) (not (eq (car p) '%template-gensym%))
                (not (eq holes '%template-mismatch%)))
      (if (consp f)
          (progn
            (setq holes (%template-match (car p) (car f) holes))
            (setq p (cdr p))
            (setq f (cdr f)))
          (setq holes '%template-mismatch%)))
    (cond ((eq holes '%template-mismatch%) holes)
          ((consp p)
           (if (%template-gensym-match (car (cdr p)) f)
               holes
               '%template-mismatch%))
          ((eq p '%template-hole%) (cons f holes))
          ((equal p f) holes)
          (t '%template-mismatch%))))

;; Whether SYMBOL can stand for the pattern's INDEXth uninterned symbol: an
;; uninterned symbol, the one the index is bound to, and no other index's.
(defun %template-gensym-match (index symbol)
  (if (%template-uninterned-p symbol)
      (let ((bound (assoc index %template-gensyms%)) (taken nil))
        (if bound
            (eq (cdr bound) symbol)
            (progn
              (dolist (entry %template-gensyms%)
                (if (eq (cdr entry) symbol) (setq taken t) nil))
              (if taken
                  nil
                  (progn
                    (setq %template-gensyms%
                          (cons (cons index symbol) %template-gensyms%))
                    t)))))
      nil))

(defun %template-uninterned-p (x)
  ;; An uninterned symbol prints #:NAME.
  (if (symbolp x)
      (let ((s (prin1-to-string x)))
        (if (> (length s) 1)
            (if (char= (char s 0) #\#) (char= (char s 1) #\:) nil)
            nil))
      nil))
