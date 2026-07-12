;; ch06/overfit_weight_decay.py -- provoking and taming overfitting with
;; weight decay (Deep Learning from Scratch).
;;
;; An oversized net is trained on only 300 images so it memorizes them:
;; train accuracy runs toward 100% while test accuracy stalls -- the gap
;; IS the overfitting. The same run with L2 weight decay (lambda = 0.1)
;; keeps train accuracy from saturating (the book's own figure shows the
;; same signature: decay mainly caps the memorization). The book trains
;; [100]*6 with lr 0.01 for 200 epochs; here a [30 30] net with lr 0.2 for
;; 30 epochs and lambda 0.05 shows the same shape.
;;
;; Data: run ../download-mnist.sh once, then from examples/deep-learning-from-scratch/:
;;   rontolisp ch06/overfit-weight-decay.lisp   (add --simd to speed it up)

(load "../dataset/mnist.lisp")
(load "../common/multi-layer-net.lisp")
(load "../common/optimizer.lisp")

(defparameter *train-limit* 300)
(defparameter *test-limit* 300)
(defparameter *batch-size* 32)
(defparameter *epochs* 20)

(defun train-once (x-train t-train x-test t-test lam)
  (linalg:seed 42)
  (let ((net (make-multi-layer-net 784 '(20 20) 10
                                   :weight-decay-lambda lam))
        (opt (make-instance 'sgd :lr 0.2))
        (iter-per-epoch (floor *train-limit* *batch-size*))
        (epoch 0))
    (dotimes (i (* *epochs* iter-per-epoch))
      (let* ((batch-mask (linalg:choice *train-limit* *batch-size*))
             (x-batch (linalg:take-rows x-train batch-mask))
             (t-batch (linalg:take-rows t-train batch-mask))
             (grads (net-gradient net x-batch t-batch)))
        (update opt (mln-params net) grads)
        (when (= (mod (+ i 1) iter-per-epoch) 0)
          (setq epoch (+ epoch 1))
          (when (= (mod epoch 6) 0)
            (format t "  epoch ~2d: train ~a/~a, test ~a/~a~%"
                    epoch
                    (net-accuracy-count net x-train t-train) *train-limit*
                    (net-accuracy-count net x-test t-test) *test-limit*)))))))

(let ((x-train (mnist-load-images "dataset/train-images-idx3-ubyte" *train-limit*))
      (t-train (mnist-load-labels "dataset/train-labels-idx1-ubyte" *train-limit* 0 t))
      (x-test (mnist-load-images "dataset/t10k-images-idx3-ubyte" *test-limit*))
      (t-test (mnist-load-labels "dataset/t10k-labels-idx1-ubyte" *test-limit* 0 t)))
  (format t "weight decay lambda = 0 (overfits):~%")
  (train-once x-train t-train x-test t-test 0)
  (format t "weight decay lambda = 0.05:~%")
  (train-once x-train t-train x-test t-test 0.05))
