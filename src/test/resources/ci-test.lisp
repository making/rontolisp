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

; defvar (idempotent global definition; returns the name symbol)
(print (defvar *gv* 100))
(print *gv*)
(defvar *gv* 200)
(print *gv*)

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

; car/cdr composition functions
(print (cadr '(1 2 3)))
(print (caddr '(1 2 3)))
(print (caar '((1 2) 3)))
(print (cadddr '(1 2 3 4)))

; Higher-order functions
(defun apply-twice (f x) (funcall f (funcall f x)))
(print (apply-twice #'square 3))

; Lambda as argument
(print (apply-twice (lambda (x) (+ x 10)) 5))

; Closure
(defun make-adder (n) (lambda (x) (+ x n)))
(setq add5 (make-adder 5))
(print (funcall add5 10))

; Closure mutation (capture by reference)
(defun make-counter () (let ((n 0)) (lambda () (setq n (+ n 1)) n)))
(setq counter (make-counter))
(funcall counter)
(funcall counter)
(print (funcall counter))

; Dynamic function selection
(setq f (if t #'square #'forty-two))
(print (funcall f 6))

; funcall
(print (funcall #'square 7))

; Type predicates
(print (if (null nil) 42 99))
(print (if (null 1) 42 99))
(print (if (atom 1) 42 99))
(print (if (atom '(1 2)) 42 99))
(print (if (numberp 42) 42 99))
(print (if (numberp 3.14) 42 99))
(print (if (integerp 42) 42 99))
(print (if (integerp 3.14) 42 99))
(print (if (floatp 3.14) 42 99))
(print (if (floatp 42) 42 99))
(print (if (symbolp 'foo) 42 99))
(print (if (symbolp 42) 42 99))
(print (if (stringp "hello") 42 99))
(print (if (stringp 42) 42 99))
(print (if (listp '(1 2)) 42 99))
(print (if (listp nil) 42 99))
(print (if (listp 42) 42 99))
(print (if (consp '(1 2)) 42 99))
(print (if (consp nil) 42 99))

; when macro
(print (when t 42))
(print (when nil 42))
(print (when t 1 2 3))
(print (when (= 1 1) 10))
(print (when (= 1 2) 10))

; append
(print (append '(1 2) '(3 4)))
(print (append nil '(1 2)))
(print (append '(1 2) nil))
(print (append '(1) '(2) '(3)))

; Convenience macros: 1+, 1-
(print (1+ 5))
(print (1- 5))

; Numeric predicates: zerop, plusp, minusp
(print (if (zerop 0) 42 99))
(print (if (zerop 1) 42 99))
(print (if (plusp 1) 42 99))
(print (if (plusp 0) 42 99))
(print (if (minusp -1) 42 99))
(print (if (minusp 0) 42 99))

; evenp, oddp
(print (if (evenp 4) 42 99))
(print (if (evenp 3) 42 99))
(print (if (oddp 3) 42 99))
(print (if (oddp 4) 42 99))

; abs, min, max
(print (abs 5))
(print (abs -5))
(print (min 3 5))
(print (max 3 5))

; unless
(print (unless nil 42))
(print (unless t 42))
(print (unless nil 1 2 3))

; while and dotimes
(print (let ((s 0)) (dotimes (i 5) (setq s (+ s i))) s))
(print (dotimes (i 3)))
(print (let ((acc 1)) (dotimes (i 4 acc) (setq acc (* acc 2)))))
(print (let ((n 0) (s 0)) (while (< n 4) (setq s (+ s n)) (setq n (+ n 1))) s))

; prog1
(print (prog1 1 2 3))
(print (let ((x (list 1 2 3))) (prog1 (car x) (setq x (cdr x)))))
(print (prog1 99))

; first, nth, nthcdr
(print (first '(1 2 3)))
(print (nth 0 '(1 2 3)))
(print (nth 2 '(1 2 3)))
(print (nthcdr 0 '(1 2 3)))
(print (nthcdr 2 '(1 2 3)))

; second, third, fourth
(print (second '(1 2 3)))
(print (third '(1 2 3)))
(print (fourth '(1 2 3 4)))

; rplaca / rplacd
(setq x (cons 1 2))
(rplaca x 10)
(print (car x))
(rplacd x 20)
(print (cdr x))

; setf
(setq y (list 1 2 3))
(setf (car y) 10)
(print (car y))
(setf (nth 1 y) 20)
(print (nth 1 y))
(print (setf (second y) 30))

; Double literals and arithmetic
(print 3.14)
(print (+ 1.5 2.5))
(print (+ 1 1.5))
(print (- 3.5 1.5))
(print (* 2.0 3.0))
(print (/ 7.0 2.0))

; Double comparison (verified via if)
(print (if (= 1.0 1.0) 42 99))
(print (if (< 1.0 2.0) 42 99))
(print (if (> 2.0 1.0) 42 99))
(print (if (<= 1.5 1.5) 42 99))
(print (if (>= 2.0 3.0) 42 99))

; Double nested arithmetic
(print (+ (* 2.0 3.0) (- 10.0 4.0)))

; cond
(print (cond (nil 1) (t 2)))
(print (cond (nil 1) (nil 2) (t 3)))
(print (cond ((= 1 2) 10) ((= 1 1) 20) (t 30)))

; case
(print (case 2 (1 'one) (2 'two) (3 'three)))
(print (case 3 (1 'one) ((2 3 4) 'small) (otherwise 'big)))
(print (case 99 (1 'one) ((2 3 4) 'small) (otherwise 'big)))
(print (case 5 (1 'a) (2 'b)))

; Type conversion: float
(print (float 42))
(print (float 3.14))
(print (float 0))

; Type conversion: truncate (toward zero)
(print (truncate 3.7))
(print (truncate -3.7))
(print (truncate 42))

; Type conversion: floor (toward negative infinity)
(print (floor 3.7))
(print (floor -3.7))
(print (floor 42))

; Type conversion: ceiling (toward positive infinity)
(print (ceiling 3.2))
(print (ceiling -3.2))
(print (ceiling 42))

; Type conversion: round (banker's rounding)
(print (round 3.5))
(print (round 2.5))
(print (round 3.7))
(print (round -3.7))
(print (round 42))

; eq (general equality)
(print (if (eq 1 1) 42 99))
(print (if (eq 1 2) 42 99))
(print (if (eq 'foo 'foo) 42 99))
(print (if (eq nil nil) 42 99))
(print (if (eq nil 1) 42 99))

; push / pop
(setq stack (list 2 3))
(push 1 stack)
(print stack)
(print (pop stack))
(print stack)

; remf
(setq plist (list 'a 1 'b 2 'c 3))
(remf plist 'b)
(print plist)
(setq plist2 (list 'x 10 'y 20))
(remf plist2 'x)
(print plist2)
(setq plist3 (list 'p 1 'q 2))
(print (if (remf plist3 'z) 42 99))

; Keyword symbols
(print :foo)
(print (if (eq :foo :foo) 42 99))
(print (if (eq :foo :bar) 42 99))
(print (if (keywordp :foo) 42 99))
(print (if (keywordp 'foo) 42 99))
(print (if (symbolp :foo) 42 99))

; mapcar
(print (mapcar (lambda (x) (* x x)) '(1 2 3)))
(print (mapcar (lambda (x) (+ x 10)) '(1 2 3)))
(print (mapcar #'square '(1 2 3)))
(print (mapcar (lambda (x) x) nil))

; reduce
(print (reduce (lambda (a b) (+ a b)) 0 '(1 2 3 4 5)))
(print (reduce (lambda (a b) (* a b)) 1 '(1 2 3 4 5)))
(print (reduce (lambda (a b) (+ a b)) '(1 2 3 4 5)))
(print (reduce (lambda (acc x) (+ acc (* x x))) 0 '(1 2 3)))
(print (reduce (lambda (a b) (+ a b)) 0 nil))

; Built-in operators as first-class values
(print (reduce #'+ 0 '(1 2 3 4 5)))
(print (reduce #'* 1 '(1 2 3 4 5)))
(print (mapcar #'car '((1 2) (3 4) (5 6))))
(print (mapcar #'cdr '((1 2) (3 4) (5 6))))
(print (mapcar #'1+ '(1 2 3)))
(print (funcall #'+ 3 4))
(setq my-op #'+)
(print (funcall my-op 10 20))

; Logical operators: and, or, not
(print (if (and) 42 99))
(print (if (and t) 42 99))
(print (if (and nil) 42 99))
(print (if (and 1 2 3) 42 99))
(print (if (and 1 nil 3) 42 99))
(print (if (or) 42 99))
(print (if (or nil) 42 99))
(print (if (or t) 42 99))
(print (if (or nil nil 3) 42 99))
(print (if (or nil nil nil) 42 99))
(print (if (not nil) 42 99))
(print (if (not 1) 42 99))
; prin1, princ, terpri
(prin1 42)
(terpri)
(prin1 "hello")
(terpri)
(princ 42)
(terpri)
(princ "hello")
(terpri)
(princ '(1 "hello" 3))
(terpri)
(prin1 1)
(princ 2)
(terpri)

; eval (interpreter, JVM, and WASM share the same runtime interpreter)
(print (eval '(+ 1 2)))
(print (eval '(* 6 7)))
(print (eval '(list 1 2 3)))
(print (eval '(let ((x 5)) (* x x))))
(print (eval '(if (= 1 1) 42 99)))
(print (eval '(cadr (list 10 20 30))))
(print (eval '(funcall (lambda (x) (+ x 1)) 41)))
(print (eval '(mapcar (lambda (x) (* x x)) (list 1 2 3))))
(print (eval '(let ((s 0)) (dotimes (i 5) (setq s (+ s i))) s)))
(print (eval '(let ((n 0) (s 0)) (while (< n 4) (setq s (+ s n)) (setq n (+ n 1))) s)))
(print (eval '(- 5)))
(print (eval '(/ 2)))

; numeric literals with comma grouping separators (e.g. 1,000 -> 1000)
(print (+ 1,000 100))
(print 1,234,567)
(print (* 2,000 3))
(print (+ 3,000.50 0.50))

; math functions supported on every backend (transcendental functions such as
; sin/cos/exp/log are interpreter/JVM-only and are not exercised here)
(print (sqrt 16))
(print (sqrt 6.25))
(print (isqrt 17))
(print (expt 2 10))
(print (gcd 48 36))
(print (lcm 4 6))
(print (signum -7))
(print (signum 3.5))
(print (mapcar #'sqrt (list 1 4 9)))

; packages: cl-user (default, uses cl) and the rontolisp package (version lives there).
; keep package switches last since they affect every following form.
(print *package*)
(print (car (rontolisp:version)))
(in-package rontolisp)
(cl:print cl:*package*)
(cl:print (cl:car (version)))

; Lisp-2: function namespace via #' / function / symbol-function and designators
(cl:in-package cl-user)
(print (funcall #'car '(9 8)))
(print (funcall (function cdr) '(9 8)))
(print (funcall 'car '(7 8)))
(print (mapcar #'cadr '((1 2) (3 4))))
(print (funcall (symbol-function 'car) '(5 6)))
(setq op2 #'+)
(print (funcall op2 20 22))
(print (mapcar #'(lambda (x) (* x 2)) '(1 2 3)))
(let ((car 5))
  (print (car (list car 2))))

; let*, dolist, incf/decf, and sequence functions
(print (let* ((x 2) (y (* x 3))) (+ x y)))
(setq dolist-sum 0)
(dolist (e '(1 2 3 4)) (setq dolist-sum (+ dolist-sum e)))
(print dolist-sum)
(print (dolist (e '(1 2) 99)))
(setq counter-n 10)
(incf counter-n)
(print counter-n)
(incf counter-n 5)
(print counter-n)
(decf counter-n 6)
(print counter-n)
(setq incf-lst (list 1 2 3))
(incf (cadr incf-lst))
(print incf-lst)
(print (length '(1 2 3 4 5)))
(print (length nil))
(print (reverse '(1 2 3)))
(print (reverse nil))
(print (member 3 '(1 2 3 4)))
(print (member 9 '(1 2 3)))
(print (assoc 'b '((a 1) (b 2) (c 3))))
(print (assoc 'z '((a 1))))
(print (last '(1 2 3)))
(print (last nil))
(print (funcall #'length '(7 8 9)))
(print (mapcar #'reverse '((1 2) (3 4))))
(print (funcall #'member 2 '(1 2 3)))
(print (find 3 '(1 2 3 4)))
(print (find 9 '(1 2 3)))
(print (funcall #'find 2 '(1 2 3)))
(print (find-if #'evenp '(1 3 5 6 7)))
(print (find-if #'oddp '(2 4 6)))
(print (funcall #'find-if #'plusp '(-1 -2 3 4)))
(print (position 3 '(1 2 3 4)))
(print (position 9 '(1 2 3)))
(print (funcall #'position 2 '(1 2 3)))
(print (count 2 '(1 2 3 2 2)))
(print (count 9 '(1 2 3)))
(print (funcall #'count 2 '(1 2 2)))

; every / some / remove / remove-if
(print (every #'evenp '(2 4 6)))
(print (every #'evenp '(2 3 6)))
(print (some #'oddp '(2 4 5)))
(print (some #'oddp '(2 4 6)))
(print (remove 2 '(1 2 3 2 4)))
(print (remove-if #'evenp '(1 2 3 4 5)))
(print (remove-if-not #'evenp '(1 2 3 4 5)))
(print (funcall #'remove 9 '(1 2 3)))

; mapcan / sort / apply
(print (mapcan (lambda (x) (list x x)) '(1 2 3)))
(print (mapcan (lambda (x) (if (evenp x) (list x) nil)) '(1 2 3 4)))
(print (funcall #'mapcan (lambda (x) (list x)) '(1 2 3)))
(print (sort '(3 1 4 1 5 9 2 6) #'<))
(print (sort '(3 1 4) #'>))
(print (funcall #'sort '(2 3 1) #'<))
(print (apply #'+ '(1 2)))
(print (apply #'cons 1 '(2)))
(print (apply (lambda (a b) (+ a b)) '(3 4)))

; rest (alias for cdr, also a setf place)
(print (rest '(1 2 3)))
(print (rest '(1)))
(setq rest-lst (list 1 2 3))
(setf (rest rest-lst) '(9))
(print rest-lst)

; rontolisp package introspection
(print (rontolisp:list-macros))
(print (rontolisp:list-special-forms))
(print (length (rontolisp:list-functions)))
(print (rontolisp:list-functions :rontolisp))
(defun ci-intro-fn (x) (+ x 1))
(print (car (member 'ci-intro-fn (rontolisp:list-functions :cl-user))))
(print (member 'car (rontolisp:list-functions :cl-user)))
(print (rontolisp:list-macros :cl-user))

; first/rest/nth as first-class function values
(print (funcall #'first '(1 2 3)))
(print (mapcar #'second '((1 2) (3 4))))
(print (funcall #'nth 1 '(7 8 9)))

; Rationals (Common Lisp ratios)
(print 1/3)
(print (/ 1 2))
(print (+ 1/2 1/3))
(print (+ 1/2 1/2))
(print (* 2/3 3))
(print (/ 1/2 1/3))
(print (/ 1 2.0))
(print (float 1/2))
(print (numerator 3/4))
(print (denominator 3/4))
(print (truncate 7/2))
(print (floor -7/2))
(print (ceiling 7/2))
(print (round 5/2))
(print (if (< 1/3 1/2) 1 0))
(print (expt 2 -1))
(print (list 1/2 2/3))
; Common Lisp fidelity: mod/rem sign, variadic comparison/min/max/gcd/lcm, length of string
(print (mod -13 4))
(print (rem -13 4))
(print (if (< 1 2 3 4) 1 0))
(print (min 5 2 8 1))
(print (max 5 2 8 1))
(print (gcd 24 36 60))
(print (lcm 2 3 4))
(print (length "hello"))
; format (destination t, directives ~a ~s ~d ~% ~~)
(format t "Hello ~a, you are ~d!~%" 'world 42)
(format t "~s and ~a~%" "str" "str")
(format t "list=~a tilde=~~~%" (list 1 2 3))
(defun ci-greet (name) (format t "Hi, ~a!~%" name))
(ci-greet 'alice)
; format nil / princ-to-string / prin1-to-string / concatenate
(princ (format nil "fmt-nil: ~a ~d ~s ~~~%" 'world 42 "str"))
(print (length (format nil "~a" 12345)))
(princ (concatenate 'string "foo" "bar"))
(terpri)
(princ (princ-to-string 3.14))
(terpri)
(princ (prin1-to-string "abc"))
(terpri)
; string operations: case conversion, subseq, comparison, trim
(princ (string-upcase "hello"))
(terpri)
(princ (string-downcase "HELLO"))
(terpri)
(princ (string-capitalize "hello world"))
(terpri)
(princ (subseq "hello world" 6))
(terpri)
(print (subseq '(1 2 3 4 5) 1 3))
(print (string= "abc" "abc"))
(print (string-equal "ABC" "abc"))
(princ (string-trim " " "  hi  "))
(terpri)
; the pi constant (output kept backend-agnostic: WASM float printing differs)
(print (truncate pi))
(print (floatp pi))
; do loop and return (non-local exit from the nearest loop block)
(print (do ((i 0 (+ i 1)) (s 0)) ((= i 5) s) (setq s (+ s i))))
(print (do ((i 0 (+ i 1)) (a 0 b) (b 1 (+ a b))) ((= i 10) a)))
(print (dolist (m '(2 3 5) t) (if (= m 3) (return))))
(print (dotimes (i 5 -1) (if (evenp i) (return i))))
(print (do ((i 0 (+ i 1))) ((> i 100) -1) (if (= i 4) (return i))))
; eql (type-aware value equality)
(print (if (eql 3 3) 42 99))
(print (if (eql 3 3.0) 42 99))
(print (if (eql 'foo 'foo) 42 99))
; eq vs eql: floats/ratios are eql (by value) but not eq (distinct objects)
(print (if (eql 1.5 1.5) 42 99))
(print (if (eq 1.5 1.5) 42 99))
(print (if (eq 3 3) 42 99))
(print (member 1.0 (list 2.0 1.0)))

; equal (structural equality): cons cells compared recursively, unlike eql
(print (equal '(1 2 (3)) '(1 2 (3))))
(print (equal '(1 2) '(1 3)))
(print (equal "abc" "abc"))
(print (equal (list 1 2) (list 1 2)))
(print (if (eql (list 1 2) (list 1 2)) 42 99))

; mapc: apply for effect, return the original list (not the mapped results)
(print (mapc #'print '(10 20)))
(print (mapc #'1+ '(1 2 3)))
