;; ch02/nand_gate.py -- the NAND perceptron (Deep Learning from Scratch).
;;
;; The AND perceptron with its weights and bias negated: w = (-0.5 -0.5),
;; b = 0.7.
;;
;;   rontolisp ch02/nand-gate.lisp

(defun nand-gate (x1 x2)
  (let* ((x (linalg:from-list (list x1 x2)))
         (w (linalg:from-list '(-0.5 -0.5)))
         (b 0.7)
         (tmp (+ (linalg:sum (linalg:mul w x)) b)))
    (if (<= tmp 0) 0 1)))

(dolist (xs '((0 0) (1 0) (0 1) (1 1)))
  (format t "(~a, ~a) -> ~a~%" (car xs) (cadr xs)
          (nand-gate (car xs) (cadr xs))))
