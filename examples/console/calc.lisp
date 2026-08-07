;;;; A tiny expression interpreter in rontolisp
;;;; Evaluates a small prefix arithmetic language, represented as ordinary
;;;; s-expressions, with a hand-written recursive evaluator over an association
;;;; list environment. Constant expressions are also cross-checked against the
;;;; built-in `eval`. Uses recursion, cond/case, alists and eval -> runs on all
;;;; three backends (interpreter / JVM / WASM).
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/console/calc.lisp
;;;;   java -jar ...-exec.jar examples/console/calc.lisp -o Calc.class && java Calc
;;;;   java -jar ...-exec.jar examples/console/calc.lisp -o calc.wasm && wasmtime run -W gc calc.wasm

;;; Look a variable up in the environment (an alist of (symbol . value)).
;;; Symbols compare by eql, so plain `assoc` is enough -- no :test needed.
(defun lookup (sym env)
  (let ((pair (assoc sym env)))
    (if pair (cdr pair) (error "unbound variable: ~a" sym))))

;;; Evaluate `expr` in `env`. The language is: numbers, variables (symbols), and
;;; binary applications (op a b) where op is one of + - * mod.
(defun my-eval (expr env)
  (cond ((numberp expr) expr)
        ((symbolp expr) (lookup expr env))
        ((consp expr)
         (let ((op (car expr))
               (a (my-eval (cadr expr) env))
               (b (my-eval (caddr expr) env)))
           (case op
             ((+) (+ a b))
             ((-) (- a b))
             ((*) (* a b))
             ((mod) (mod a b))
             (t (error "unknown operator: ~a" op)))))
        (t (error "cannot evaluate: ~a" expr))))

;;; An environment binding x and y.
(defparameter *env* (list (cons 'x 10) (cons 'y 3)))

(format t "Evaluating with env x=10, y=3:~%")
(dolist (p '((+ 1 (* 2 3)) (- (* x x) (* y y)) (mod (+ x y) 5)))
  (format t "  ~a => ~a~%" p (my-eval p *env*)))

(format t "~%Cross-check against the built-in eval (constant expressions):~%")
(dolist (p '((+ 1 (* 2 3)) (- 100 (* 7 8)) (* (+ 1 2) (+ 3 4))))
  (format t "  ~a : my-eval=~a  eval=~a~%" p (my-eval p nil) (eval p)))
