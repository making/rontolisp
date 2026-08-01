;; The COMPILE runtime of a compiled program. Injected ONLY into programs that
;; reference compile without defining it (LispMacroExpander gate), so every
;; other program stays byte-identical.
;;
;; A compiled program has no runtime compiler, so this defun exists for exactly
;; one caller shape: the (funcall (compile nil `(lambda () ,code))) idiom of
;; postmodern's build-dao-methods, whose method construction ALREADY ran at
;; definition time -- the evaluator's compile intercepted it and spliced the
;; folded method definitions into the program as top-level forms ("expand and
;; splice", see MopEvalCapture). When the class-definition protocol re-runs the
;; same call at program start, the work is already in the program, so a
;; definition that defines methods answers a do-nothing function. Every other
;; definition signals: silently pretending to compile it would hide a real
;; behavioral gap.

(defun compile (name definition)
  (if (%compile-defines-methods-p definition)
      (lambda () nil)
      (error "COMPILE: runtime compilation is not supported in compiled programs")))

(defun %compile-defines-methods-p (form)
  (if (consp form)
      (if (eq (car form) 'defmethod)
          t
          (if (%compile-defines-methods-p (car form))
              t
              (%compile-defines-methods-p (cdr form))))
      nil))
