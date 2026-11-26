;; ch06/weight_init_activation_histogram.py -- activation distributions
;; under different weight initializations (Deep Learning from Scratch).
;;
;; 1000 samples flow through five 100-unit sigmoid layers; the book
;; histograms each layer's activations for std=1, std=0.01 and the Xavier
;; initialization. Text buckets replace the plots: std=1 saturates at 0/1
;; (vanishing gradients), std=0.01 collapses to 0.5 (no representation
;; power), Xavier stays spread out -- the book's three panels.
;;
;;   rontolisp ch06/weight-init-activation-histogram.lisp

(load "../common/functions.lisp")

(defparameter *hidden-layer-num* 5)
(defparameter *node-num* 100)

(defun activation-histogram (init-name w-scale)
  (linalg:seed 1)
  (let ((x (linalg:randn '(1000 100))) (activations nil))
    (dotimes (i *hidden-layer-num*)
      (let* ((w
              (linalg:mul w-scale (linalg:randn (list *node-num* *node-num*))))
             (a (linalg:matmul x w))
             (z (sigmoid a)))
        (setq activations (cons z activations))
        (setq x z)))
    (format t "~a:~%" init-name)
    (let ((idx 0))
      (dolist (z (reverse activations))
        (setq idx (+ idx 1))
        ;; ten buckets over [0, 1]
        (let ((counts (make-array 10 :initial-element 0)))
          (dotimes (k (linalg:size z))
            (let ((b (min 9 (truncate (* 10 (row-major-aref z k))))))
              (setf (aref counts b) (+ (aref counts b) 1))))
          (format t "  layer ~a:" idx)
          (dotimes (b 10) (format t " ~5d" (aref counts b)))
          (format t "~%"))))))

(activation-histogram "std = 1.0" 1.0)
(activation-histogram "std = 0.01" 0.01)
(activation-histogram "Xavier (sqrt(1/n))" (sqrt (/ 1.0 100)))
