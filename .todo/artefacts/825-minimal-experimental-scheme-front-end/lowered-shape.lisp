;; What a Scheme front end would EMIT (hand-written). User identifiers stay lowercase
;; (case-sensitive), so they can never collide with the upcased CL names.

;; 1. a user procedure whose name is a CL macro / function name
(defun |loop| (|list|) (car |list|))
(print (|loop| '(1 2 3)))

;; 2. Lisp-1: a define'd name that is assigned -> top-level setq (NOT defvar, see
;;    global-define.lisp.in) + funcall
(setq |f| (lambda (|x|) (* |x| 2)))
(setq |f| (lambda (|x|) (* |x| 3)))
(print (funcall |f| 7))

;; 3. '() = NIL, #f = a distinct value; `if` tests against #f
(setq |%false| '|#f|)
(defun |truthy| (|x|) (if (eq |x| |%false|) 'no 'yes))
(print (list (|truthy| '()) (|truthy| |%false|) (|truthy| 0)))

;; 4. named let / self tail call lowered to a loop: 1,000,000 iterations
(defun |count| (|n|)
  (let ((|i| 0) (|acc| 0))
    (tagbody
     top
       (if (< |i| |n|)
           (progn (setq |acc| (+ |acc| |i|)) (setq |i| (+ |i| 1)) (go top))))
    |acc|))
(print (|count| 1000000))

;; 5. escape-only call/cc: k is a first-class closure that escapes through a lambda
(defun |call/ec| (|proc|)
  (block |k| (funcall |proc| (lambda (|v|) (return-from |k| |v|)))))
(print (+ 1 (|call/ec| (lambda (|k|) (mapcar (lambda (|x|) (if (> |x| 2) (funcall |k| |x|) |x|)) '(1 2 3 4)) 99))))

;; 6. dynamic-wind (exit half) over that escape
(defun |dynamic-wind| (|before| |thunk| |after|)
  (funcall |before|)
  (unwind-protect (funcall |thunk|) (funcall |after|)))
(print (|call/ec| (lambda (|k|)
  (|dynamic-wind| (lambda () (print 'in)) (lambda () (funcall |k| 'escaped)) (lambda () (print 'out))))))

;; 7. variadic + apply
(defun |sum| (&rest |args|) (apply #'+ |args|))
(print (apply #'|sum| 1 2 '(3 4)))
(print (funcall (lambda (|a| &rest |r|) (list |a| |r|)) 1 2 3))
