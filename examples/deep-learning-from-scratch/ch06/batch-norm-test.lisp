;; ch06/batch_norm_test.py -- does Batch Normalization help? (Deep
;; Learning from Scratch).
;;
;; The book trains a with-BN / without-BN pair for 16 weight-init scales
;; and plots the accuracy curves; here two representative scales are run
;; (a healthy 0.1 and a starved 0.01) and the per-epoch train accuracies
;; are printed side by side. With badly scaled initial weights the plain
;; net cannot start learning while the BN net can -- the book's punchline.
;;
;; Data: run ../download-mnist.sh once, then from examples/deep-learning-from-scratch/:
;;   rontolisp ch06/batch-norm-test.lisp   (add --simd to speed it up)

(load "../dataset/mnist.lisp")
(load "../common/multi-layer-net-extend.lisp")
(load "../common/optimizer.lisp")

(defparameter *train-limit* 300)
(defparameter *batch-size* 16)
(defparameter *epochs* 3)

(defun train-once (x-train t-train w-scale use-bn)
  ;; Returns the list of per-epoch train-accuracy counts.
  (linalg:seed 42)
  (let ((net (make-multi-layer-net-extend 784 '(20 20) 10
                                          :weight-init-std w-scale
                                          :use-batchnorm use-bn))
        (opt (make-instance 'sgd :lr 0.5))
        (iter-per-epoch (floor *train-limit* *batch-size*))
        (accs nil))
    (dotimes (i (* *epochs* iter-per-epoch))
      (let* ((batch-mask (linalg:choice *train-limit* *batch-size*))
             (x-batch (linalg:take-rows x-train batch-mask))
             (t-batch (linalg:take-rows t-train batch-mask))
             (grads (net-gradient net x-batch t-batch)))
        (update opt (mlne-params net) grads)
        (when (= (mod (+ i 1) iter-per-epoch) 0)
          (setq accs (cons (net-accuracy-count net x-train t-train) accs)))))
    (reverse accs)))

(let ((x-train (mnist-load-images "dataset/train-images-idx3-ubyte" *train-limit*))
      (t-train (mnist-load-labels "dataset/train-labels-idx1-ubyte" *train-limit* 0 t)))
  (dolist (w-scale '(0.1 0.01))
    (format t "w-scale = ~a (train acc per epoch, of ~a):~%" w-scale *train-limit*)
    (format t "  with BatchNorm:    ~a~%" (train-once x-train t-train w-scale t))
    (format t "  without BatchNorm: ~a~%" (train-once x-train t-train w-scale nil))))
