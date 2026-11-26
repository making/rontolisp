;;;; Derivatives without symbolic rules: forward-mode automatic differentiation.
;;;; A dual number carries a value together with its slope with respect to the
;;;; input, and every arithmetic step updates both. A function is a quoted
;;;; expression over x; running it on the dual (x0, 1) gives f(x0) and f'(x0) in
;;;; one pass. Exact arithmetic throughout, so every slope is an exact rational.
;;;;
;;;; Run:
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/scheme/differentiation.scm

(import (scheme base) (scheme write))

(define-record-type dual
  (make-dual value slope)
  dual?
  (value dual-value)
  (slope dual-slope))

(define (constant c) (make-dual c 0))

(define (dual-add a b)
  (make-dual (+ (dual-value a) (dual-value b))
             (+ (dual-slope a) (dual-slope b))))

(define (dual-negate a) (make-dual (- (dual-value a)) (- (dual-slope a))))

(define (dual-mul a b)
  (let ((u (dual-value a)) (v (dual-value b)))
    (make-dual (* u v) (+ (* (dual-slope a) v) (* u (dual-slope b))))))

(define (dual-div a b)
  (let ((u (dual-value a)) (v (dual-value b)))
    (make-dual (/ u v)
               (/ (- (* (dual-slope a) v) (* u (dual-slope b))) (* v v)))))

;; u^k for an exact integer k: the slope is k u^(k-1) u'.
(define (dual-power a k)
  (if (= k 0)
      (constant 1)
      (let ((u (dual-value a)))
        (make-dual (expt u k) (* k (expt u (- k 1)) (dual-slope a))))))

;; Combine any number of operands left to right, starting from unit.
(define (combine op unit operands)
  (let loop ((acc unit) (rest operands))
    (if (null? rest) acc (loop (op acc (car rest)) (cdr rest)))))

;; Run expression e with x bound to the dual x.
(define (run e x)
  (cond ((number? e) (constant e))
        ((eq? e 'x) x)
        ((pair? e)
         (let ((args (map (lambda (sub) (run sub x)) (cdr e))))
           (case (car e)
             ((+) (combine dual-add (constant 0) args))
             ((*) (combine dual-mul (constant 1) args))
             ((-)
              (let ((rest (combine dual-add (constant 0) (cdr args))))
                (if (null? (cdr args))
                    (dual-negate (car args))
                    (dual-add (car args) (dual-negate rest)))))
             ((/) (dual-div (car args) (cadr args)))
             ((^) (dual-power (car args) (dual-value (cadr args))))
             (else (error "unknown operator" (car e))))))
        (else (error "unknown expression" e))))

(define (at e x0) (run e (make-dual x0 1)))

(define functions
  '((+ (* 3 (^ x 2)) (* -4 x) 7)
    (* (+ x 1) (- x 1) (+ x 2))
    (^ (- (* 2 x) 1) 5)
    (/ 1 (+ (* x x) 1))
    (/ (- x 1) (+ x 1))))

(define points '(-1/2 0 1/2 1 3))

(define (show . items)
  (for-each (lambda (item) (if (string? item) (display item) (write item)))
            items))

(for-each (lambda (f)
            (show "f(x) = " f)
            (newline)
            (for-each (lambda (x0)
                        (let ((d (at f x0)))
                          (show "  x = " x0 "  f = " (dual-value d) "  f' = "
                                (dual-slope d))
                          (newline))) points)
            (let* ((d (at f 1)) (m (dual-slope d)) (b (- (dual-value d) m)))
              (show "  tangent at x = 1: y = " m " x " (if (< b 0) "- " "+ ")
                    (abs b))
              (newline))) functions)

;; The slope at a point where the derivative vanishes: the vertex of a parabola.
(define parabola '(+ (* 3 (^ x 2)) (* -4 x) 7))
(define vertex 2/3)
(show "slope of " parabola " at its vertex " vertex ": "
      (dual-slope (at parabola vertex)))
(newline)
