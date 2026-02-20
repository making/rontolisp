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
