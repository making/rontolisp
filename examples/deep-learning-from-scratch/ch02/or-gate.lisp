;; ch02/or_gate.py -- the OR perceptron (Deep Learning from Scratch).
;;
;; w = (0.5 0.5) like AND, but the weaker bias b = -0.2 lets a single
;; active input fire the perceptron.
;;
;;   rontolisp ch02/or-gate.lisp

(defun or-gate (x1 x2)
  (let* ((x (linalg:from-list (list x1 x2)))
         (w (linalg:from-list '(0.5 0.5)))
         (b -0.2)
         (tmp (+ (linalg:sum (linalg:mul w x)) b)))
    (if (<= tmp 0) 0 1)))

(dolist (xs '((0 0) (1 0) (0 1) (1 1)))
  (format t "(~a, ~a) -> ~a~%" (car xs) (cadr xs) (or-gate (car xs) (cadr xs))))
