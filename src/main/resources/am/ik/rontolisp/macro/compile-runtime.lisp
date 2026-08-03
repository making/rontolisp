;; The COMPILE runtime of a compiled program. Injected ONLY into programs that
;; reference compile without defining it (LispMacroExpander gate), so every
;; other program stays byte-identical.
;;
;; A compiled program has no runtime compiler, and two caller shapes reach here.
;;
;; The first is the (funcall (compile nil `(lambda () ,code))) idiom of
;; postmodern's build-dao-methods, whose method construction ALREADY ran at
;; definition time -- the evaluator's compile intercepted it and spliced the
;; folded method definitions into the program as top-level forms ("expand and
;; splice", see MopEvalCapture). When the class-definition protocol re-runs the
;; same call at program start, the work is already in the program, so a
;; definition that defines methods answers a do-nothing function.
;;
;; Everything else is EVALUATED (.kb/eval-runtime.md). That is compile's own
;; contract where there is no compiler: CL says (compile nil lambda-expression)
;; answers a function equivalent to the lambda expression closed in the NULL
;; lexical environment, which is exactly what eval of the same form answers --
;; only slower. esrap builds a trampoline this way for a semantic predicate whose
;; symbol it does not own: (compile nil `(lambda ,args (,name ,@args))).
;; Referencing eval here also GATES the eval runtime into the program, so a
;; program that only calls compile still carries the interpreter it needs.

(defun compile (name definition)
  (if (%compile-defines-methods-p definition)
      (lambda () nil)
      (eval definition)))

(defun %compile-defines-methods-p (form)
  (if (consp form)
      (if (eq (car form) 'defmethod)
          t
          (if (%compile-defines-methods-p (car form))
              t
              (%compile-defines-methods-p (cdr form))))
      nil))
