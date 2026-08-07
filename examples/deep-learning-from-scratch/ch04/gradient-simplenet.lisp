;; ch04/gradient_simplenet.py -- the numerical gradient of a one-matrix
;; network's loss (Deep Learning from Scratch).
;;
;; simpleNet holds one 2x3 weight matrix W; the loss is the softmax
;; cross-entropy of x.W against the one-hot target t. The numerical
;; gradient dW is what the book's ch04 climax computes. Weights come from
;; linalg:randn under a fixed seed, so the output is deterministic (and,
;; up to WASM's exp/log approximation, backend-identical).
;;
;;   rontolisp ch04/gradient-simplenet.lisp

(load "../common/functions.lisp")
(load "../common/gradient.lisp")

(linalg:seed 0)

(defparameter *w* (linalg:randn '(2 3)))

(defun net-predict (x) (linalg:dot x *w*))

(defun net-loss (x target)
  (cross-entropy-error (softmax (net-predict x)) target))

(let* ((x (linalg:from-list '(0.6 0.9)))
       (target (linalg:from-list '(0 0 1)))
       (f (lambda (w) (net-loss x target)))
       (dw (numerical-gradient f *w*)))
  (format t "W:~%")
  (dotimes (i 2)
    (format t "  ~,6f ~,6f ~,6f~%" (aref *w* i 0) (aref *w* i 1)
            (aref *w* i 2)))
  (format t "loss: ~,6f~%" (net-loss x target))
  (format t "dW:~%")
  (dotimes (i 2)
    (format t "  ~,6f ~,6f ~,6f~%" (aref dw i 0) (aref dw i 1) (aref dw i 2))))
