;; ch02/and_gate.py -- the AND perceptron (Deep Learning from Scratch).
;;
;; A single perceptron with hand-picked weights w = (0.5 0.5) and bias
;; b = -0.7: fires when w.x + b > 0. Pure arithmetic, so the output is
;; byte-identical on every backend.
;;
;;   rontolisp ch02/and-gate.lisp

(defun and-gate (x1 x2)
  (let* ((x (linalg:from-list (list x1 x2)))
         (w (linalg:from-list '(0.5 0.5)))
         (b -0.7)
         (tmp (+ (linalg:sum (linalg:mul w x)) b)))
    (if (<= tmp 0) 0 1)))

(dolist (xs '((0 0) (1 0) (0 1) (1 1)))
  (format t "(~a, ~a) -> ~a~%" (car xs) (cadr xs)
          (and-gate (car xs) (cadr xs))))
