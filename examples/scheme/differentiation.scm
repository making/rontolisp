;;;; Symbolic differentiation with simplification.
;;;; Expressions are lists: (+ a b), (- a b), (* a b), (expt u n), numbers and
;;;; variables. `deriv` follows the textbook rules; the constructors fold constants
;;;; and drop identities so the answers stay readable. Exact arithmetic only.
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/scheme/differentiation.scm

(import (scheme base) (scheme cxr) (scheme write))

(define (variable? e) (symbol? e))
(define (op? e name) (and (pair? e) (eq? (car e) name)))

;; Constructors that simplify as they build.
(define (make-sum a b)
  (cond ((and (number? a) (number? b)) (+ a b))
        ((eqv? a 0) b)
        ((eqv? b 0) a)
        ((equal? a b) (make-product 2 a))
        (else (list '+ a b))))

(define (make-difference a b)
  (cond ((and (number? a) (number? b)) (- a b))
        ((eqv? b 0) a)
        ((equal? a b) 0)
        (else (list '- a b))))

(define (make-product a b)
  (cond ((and (number? a) (number? b)) (* a b))
        ((or (eqv? a 0) (eqv? b 0)) 0)
        ((eqv? a 1) b)
        ((eqv? b 1) a)
        ((and (number? b) (not (number? a))) (make-product b a))
        ((and (number? a) (op? b '*) (number? (cadr b)))
         (make-product (* a (cadr b)) (caddr b)))
        (else (list '* a b))))

(define (make-power base n)
  (cond ((eqv? n 0) 1)
        ((eqv? n 1) base)
        ((number? base) (expt base n))
        (else (list 'expt base n))))

(define (deriv e var)
  (cond ((number? e) 0)
        ((variable? e) (if (eq? e var) 1 0))
        ((op? e '+) (make-sum (deriv (cadr e) var) (deriv (caddr e) var)))
        ((op? e '-)
         (make-difference (deriv (cadr e) var) (deriv (caddr e) var)))
        ((op? e '*)
         (let ((u (cadr e)) (v (caddr e)))
           (make-sum (make-product u (deriv v var))
                     (make-product (deriv u var) v))))
        ((op? e 'expt)
         (let ((u (cadr e)) (n (caddr e)))
           (make-product (make-product n (make-power u (- n 1)))
                         (deriv u var))))
        (else (error "unknown expression" e))))

;; Evaluate an expression with its variables bound by an association list.
(define (evaluate e env)
  (cond ((number? e) e)
        ((variable? e)
         (let ((binding (assq e env)))
           (if binding (cdr binding) (error "unbound variable" e))))
        (else
         (let ((args (map (lambda (x) (evaluate x env)) (cdr e))))
           (case (car e)
             ((+) (apply + args))
             ((-) (apply - args))
             ((*) (apply * args))
             ((expt) (expt (car args) (cadr args)))
             (else (error "unknown operator" (car e))))))))

(define (show label value)
  (display label)
  (write value)
  (newline))

(define examples
  '((+ x 3)
    (* x y)
    (* (* x y) (+ x 3))
    (expt (+ (* 3 x) 1) 4)
    (- (* x x) (* 2 x))
    (+ (* 1/2 (expt x 2)) (* 5 x))))

(for-each (lambda (e)
            (let ((d (deriv e 'x)))
              (show "f(x)  = " e)
              (show "f'(x) = " d)
              (show "f'(2) = " (evaluate d '((x . 2) (y . 7))))
              (newline))) examples)

;; Higher derivatives by repeated application.
(define (nth-deriv e var n)
  (let loop ((e e) (n n)) (if (= n 0) e (loop (deriv e var) (- n 1)))))

(do ((k 0 (+ k 1))) ((> k 6))
  (display "d^")
  (display k)
  (display " x^5 = ")
  (write (nth-deriv '(expt x 5) 'x k))
  (newline))
