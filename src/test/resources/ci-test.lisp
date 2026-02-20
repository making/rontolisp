; Comprehensive CI test for all compiler features
; Each (print ...) call produces one line of output

; Arithmetic
(print (+ 1 2))
(print (- 10 3))
(print (* 3 4))
(print (/ 10 3))
(print (mod 10 3))

; Nested arithmetic
(print (+ (* 2 3) (- 10 4)))

; Comparison operators (verified via if)
(print (if (= 1 1) 42 99))
(print (if (< 1 2) 42 99))
(print (if (> 3 2) 42 99))
(print (if (<= 2 2) 42 99))
(print (if (>= 2 3) 42 99))

; Conditionals
(print (if t 1 2))
(print (if nil 1 2))

; let binding
(print (let ((x 10) (y 20)) (+ x y)))

; progn
(print (progn 1 2 3))

; setq
(print (progn (setq x 10) x))
(print (progn (setq a 10) (setq a 20) a))

; defun
(defun square (x) (* x x))
(print (square 5))

; defun with no arguments
(defun forty-two () 42)
(print (forty-two))

; Recursion: factorial
(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
(print (fact 5))

; Recursion: fibonacci
(defun fib (n) (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))
(print (fib 10))

; Lambda immediate invocation
(print ((lambda (x) (* x x)) 5))

; Quote
(print '42)
(print '(1 2 3))
(print '(1 (2 3) 4))
(print (quote nil))
(print '(+ 1 2))

; String literals
(print "hello")
(let ((s "world")) (print s))

; list / car / cdr / cons
(print (car (cons 1 2)))
(print (cdr (cons 1 2)))
(print (car (list 1 2 3)))
(print (car (cdr (list 1 2 3))))

; Higher-order functions
(defun apply-twice (f x) (f (f x)))
(print (apply-twice square 3))

; Lambda as argument
(print (apply-twice (lambda (x) (+ x 10)) 5))

; Closure
(defun make-adder (n) (lambda (x) (+ x n)))
(setq add5 (make-adder 5))
(print (add5 10))

; Closure mutation (capture by reference)
(defun make-counter () (let ((n 0)) (lambda () (setq n (+ n 1)) n)))
(setq counter (make-counter))
(counter)
(counter)
(print (counter))

; Dynamic function selection
(setq f (if t square forty-two))
(print (f 6))

; funcall
(print (funcall square 7))
