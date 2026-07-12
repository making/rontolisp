;; ch06/optimizer_compare_naive.py -- SGD / Momentum / AdaGrad / Adam on
;; f(x, y) = x^2/20 + y^2 (Deep Learning from Scratch).
;;
;; The book draws each optimizer's trajectory over the contour plot; here
;; every 5th position is printed instead. The anisotropic bowl is the
;; point: SGD zigzags, Momentum overshoots and swings back, AdaGrad's
;; per-axis scaling goes straight, Adam curves gently. The gradient is
;; analytic (df = x/10, 2y) -- no exp/log -- so the output is
;; byte-identical on every backend.
;;
;;   rontolisp ch06/optimizer-compare-naive.lisp

(load "../common/optimizer.lisp")

;; params/grads hold 1-element linalg vectors so the shared optimizer
;; update (element loops over arrays) applies unchanged.
(defun run-optimizer (name opt)
  (let ((params (make-hash-table :test 'equal))
        (grads (make-hash-table :test 'equal)))
    (setf (gethash "x" params) (linalg:from-list '(-7.0)))
    (setf (gethash "y" params) (linalg:from-list '(2.0)))
    (setf (gethash "x" grads) (linalg:zeros 1))
    (setf (gethash "y" grads) (linalg:zeros 1))
    (format t "~a:~%" name)
    (let ((px (gethash "x" params))
          (py (gethash "y" params))
          (gx (gethash "x" grads))
          (gy (gethash "y" grads)))
      (dotimes (i 30)
        (when (= (mod i 5) 0)
          (format t "  step ~2d: (~,6f, ~,6f)~%" i (aref px 0) (aref py 0)))
        (setf (aref gx 0) (/ (aref px 0) 10.0))
        (setf (aref gy 0) (* 2.0 (aref py 0)))
        (update opt params grads))
      (format t "  final:   (~,6f, ~,6f)~%" (aref px 0) (aref py 0)))))

(run-optimizer "SGD" (make-instance 'sgd :lr 0.95))
(run-optimizer "Momentum" (make-instance 'momentum :lr 0.1))
(run-optimizer "AdaGrad" (make-instance 'adagrad :lr 1.5))
(run-optimizer "Adam" (make-instance 'adam :lr 0.3))
