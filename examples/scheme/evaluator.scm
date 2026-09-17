;;;; A small metacircular evaluator: a Scheme interpreter written in Scheme. It
;;;; handles quote, if, define, set!, lambda, begin, let and cond, with
;;;; environments as a chain of frames and a handful of primitives borrowed
;;;; from the host. The program it runs is a quoted list of expressions in this
;;;; file; each result is printed as it is evaluated.
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/scheme/evaluator.scm

(import (scheme base) (scheme cxr) (scheme write))

;;; Environments: a list of frames, each frame a mutable list of (name . value).

(define (extend-environment names values env)
  (if (= (length names) (length values))
      (cons (map cons names values) env)
      (error "wrong number of arguments" names values)))

(define (lookup-binding name env)
  (if (null? env)
      #f
      (or (assq name (car env)) (lookup-binding name (cdr env)))))

(define (lookup-variable name env)
  (let ((binding (lookup-binding name env)))
    (if binding (cdr binding) (error "unbound variable" name))))

(define (set-variable! name value env)
  (let ((binding (lookup-binding name env)))
    (if binding (set-cdr! binding value) (error "set! of an unbound variable" name))))

(define (define-variable! name value env)
  (let ((binding (assq name (car env))))
    (if binding
        (set-cdr! binding value)
        (set-car! env (cons (cons name value) (car env))))))

;;; Procedures.

(define-record-type compound
  (make-compound parameters body env)
  compound?
  (parameters compound-parameters)
  (body compound-body)
  (env compound-env))

(define-record-type primitive
  (make-primitive name implementation)
  primitive?
  (name primitive-name)
  (implementation primitive-implementation))

;;; The evaluator proper.

(define (tagged? expr tag) (and (pair? expr) (eq? (car expr) tag)))

(define (m-eval expr env)
  (cond ((or (number? expr) (string? expr) (boolean? expr)) expr)
        ((symbol? expr) (lookup-variable expr env))
        ((tagged? expr 'quote) (cadr expr))
        ((tagged? expr 'if)
         (if (true? (m-eval (cadr expr) env))
             (m-eval (caddr expr) env)
             (if (null? (cdddr expr)) #f (m-eval (cadddr expr) env))))
        ((tagged? expr 'define) (eval-define expr env))
        ((tagged? expr 'set!)
         (set-variable! (cadr expr) (m-eval (caddr expr) env) env)
         'ok)
        ((tagged? expr 'lambda) (make-compound (cadr expr) (cddr expr) env))
        ((tagged? expr 'begin) (eval-sequence (cdr expr) env))
        ((tagged? expr 'let) (m-eval (let->combination expr) env))
        ((tagged? expr 'cond) (m-eval (cond->if (cdr expr)) env))
        ((pair? expr)
         (m-apply (m-eval (car expr) env)
                  (map (lambda (operand) (m-eval operand env)) (cdr expr))))
        (else (error "unknown expression type" expr))))

(define (true? value) (not (eq? value #f)))

(define (eval-define expr env)
  (let ((target (cadr expr)))
    (if (pair? target)
        ;; (define (name . params) body ...)
        (define-variable! (car target)
                          (make-compound (cdr target) (cddr expr) env)
                          env)
        (define-variable! target (m-eval (caddr expr) env) env))
    (if (pair? target) (car target) target)))

(define (eval-sequence exprs env)
  (if (null? (cdr exprs))
      (m-eval (car exprs) env)
      (begin (m-eval (car exprs) env)
             (eval-sequence (cdr exprs) env))))

;; (let ((n v) ...) body ...) => ((lambda (n ...) body ...) v ...)
(define (let->combination expr)
  (cons (cons 'lambda (cons (map car (cadr expr)) (cddr expr)))
        (map cadr (cadr expr))))

;; (cond (test e ...) ... (else e ...)) => nested ifs
(define (cond->if clauses)
  (cond ((null? clauses) #f)
        ((eq? (caar clauses) 'else) (cons 'begin (cdar clauses)))
        (else (list 'if
                    (caar clauses)
                    (cons 'begin (cdar clauses))
                    (cond->if (cdr clauses))))))

(define (m-apply procedure arguments)
  (cond ((primitive? procedure)
         (apply (primitive-implementation procedure) arguments))
        ((compound? procedure)
         (eval-sequence (compound-body procedure)
                        (extend-environment (compound-parameters procedure)
                                            arguments
                                            (compound-env procedure))))
        (else (error "not a procedure" procedure))))

;;; The global environment.

(define (setup-environment)
  (extend-environment
   '(+ - * = < > car cdr cons null? list display newline)
   (map (lambda (entry) (make-primitive (car entry) (cdr entry)))
        (list (cons '+ +) (cons '- -) (cons '* *) (cons '= =) (cons '< <) (cons '> >)
              (cons 'car car) (cons 'cdr cdr) (cons 'cons cons) (cons 'null? null?)
              (cons 'list list) (cons 'display display) (cons 'newline newline)))
   '()))

;; How a value of the evaluated language is shown.
(define (show-value value)
  (cond ((compound? value) (display "#<procedure>"))
        ((primitive? value) (display "#<primitive ") (display (primitive-name value)) (display ">"))
        (else (write value))))

(define program
  '((define (factorial n)
      (if (= n 0) 1 (* n (factorial (- n 1)))))
    (factorial 20)
    (define (map f items)
      (if (null? items) '() (cons (f (car items)) (map f (cdr items)))))
    (map (lambda (x) (* x x)) (list 1 2 3 4 5))
    (define (make-counter)
      (let ((count 0))
        (lambda () (set! count (+ count 1)) count)))
    (define tick (make-counter))
    (tick)
    (tick)
    (tick)
    (define (fib n)
      (cond ((< n 2) n)
            (else (+ (fib (- n 1)) (fib (- n 2))))))
    (fib 15)
    ((lambda (x y) (list y x)) 'first 'second)
    (begin (display "side effect inside the evaluated program") (newline) 'done)
    car))

(define global-environment (setup-environment))

(for-each
 (lambda (expr)
   (display ";; ")
   (write expr)
   (newline)
   (let ((value (m-eval expr global-environment)))
     (display "=> ")
     (show-value value)
     (newline)))
 program)
