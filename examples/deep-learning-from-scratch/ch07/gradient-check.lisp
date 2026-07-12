;; ch07/gradient_check.py -- backprop vs numerical gradients through the
;; CNN (Deep Learning from Scratch).
;;
;; The correctness gate of the Convolution/Pooling layers: the analytic
;; gradients from backprop (im2col/col2im, the argmax scatter) must agree
;; with the ch04 central differences. The book checks a synthetic 10x10
;; single image through a filter_num=10 SimpleConvNet; numerically
;; sweeping its 3400 parameters is nearly a minute of interpreter time,
;; so this port shrinks filter-num to 3 (630 parameters) -- the same
;; mathematics, no data files, seconds anywhere. weight-init-std is 0.1
;; instead of the default 0.01: with 0.01-scale activations the h = 1e-4
;; central difference itself crosses ReLU kinks and flips pooling argmax
;; ties, polluting the NUMERICAL side to ~1e-5 (backprop is exact either
;; way). The raw mean differences are ~1e-8 and their last digits differ
;; per backend, so each key prints a PASS/FAIL verdict against 1e-6
;; instead of the raw float.
;;
;;   rontolisp ch07/gradient-check.lisp

(load "simple-convnet.lisp")

(linalg:seed 42)

(let* ((net (make-simple-convnet :input-dim '(1 10 10)
                                 :filter-num 3 :filter-size 3
                                 :filter-pad 0 :filter-stride 1
                                 :hidden-size 10 :output-size 10
                                 :weight-init-std 0.1))
       (x-batch (linalg:reshape (linalg:rand 100) '(1 1 10 10)))
       (t-batch (linalg:one-hot #(1) 10))
       (grad-numerical (net-numerical-gradient net x-batch t-batch))
       (grad-backprop (net-gradient net x-batch t-batch)))
  (dolist (key *scn-keys*)
    (let* ((gn (gethash key grad-numerical))
           (gb (gethash key grad-backprop))
           (diff (/ (linalg:sum (linalg:abs (linalg:sub gb gn)))
                    (linalg:size gn))))
      (format t "~a: ~a~%" key
              (if (< diff 1.0e-6) "PASS (mean |diff| < 1e-6)" "FAIL")))))
