;; ch02/xor_gate.py -- XOR from stacked perceptrons (Deep Learning from
;; Scratch).
;;
;; XOR is not linearly separable, so no single perceptron computes it; the
;; book's punchline is that one extra layer does: XOR(x1, x2) =
;; AND(NAND(x1, x2), OR(x1, x2)). The Python script imports the three gates
;; from their modules; a rontolisp load would also run their truth tables
;; (no `if __name__ == "__main__"` guard exists), so the gates are defined
;; here again.
;;
;;   rontolisp ch02/xor-gate.lisp

(defun %gate (x1 x2 w1 w2 b)
  ;; One perceptron: fires when w.x + b > 0.
  (let* ((x (linalg:from-list (list x1 x2)))
         (w (linalg:from-list (list w1 w2)))
         (tmp (+ (linalg:sum (linalg:mul w x)) b)))
    (if (<= tmp 0) 0 1)))

(defun and-gate (x1 x2) (%gate x1 x2 0.5 0.5 -0.7))
(defun nand-gate (x1 x2) (%gate x1 x2 -0.5 -0.5 0.7))
(defun or-gate (x1 x2) (%gate x1 x2 0.5 0.5 -0.2))

(defun xor-gate (x1 x2)
  (let ((s1 (nand-gate x1 x2)) (s2 (or-gate x1 x2))) (and-gate s1 s2)))

(dolist (xs '((0 0) (1 0) (0 1) (1 1)))
  (format t "(~a, ~a) -> ~a~%" (car xs) (cadr xs)
          (xor-gate (car xs) (cadr xs))))
