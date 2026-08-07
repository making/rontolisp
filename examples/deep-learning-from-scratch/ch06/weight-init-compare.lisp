;; ch06/weight_init_compare.py -- std=0.01 vs Xavier vs He on MNIST (Deep
;; Learning from Scratch).
;;
;; Three identical [30 30] ReLU nets, differing only in weight
;; initialization, race under plain SGD: std=0.01 stalls (its activations
;; carry no signal), Xavier learns, He learns fastest with ReLU -- the
;; book's figure as printed losses.
;;
;; Data: run ../download-mnist.sh once, then from examples/deep-learning-from-scratch/:
;;   rontolisp ch06/weight-init-compare.lisp   (add --simd to speed it up)

(load "../dataset/mnist.lisp")
(load "../common/multi-layer-net.lisp")
(load "../common/optimizer.lisp")

(defparameter *train-limit* 500)
(defparameter *batch-size* 16)
(defparameter *iters* 100)

(let ((x-train
       (mnist-load-images "dataset/train-images-idx3-ubyte" *train-limit*))
      (t-train
       (mnist-load-labels "dataset/train-labels-idx1-ubyte" *train-limit* 0 t)))
  (dolist (entry
           (list (list "std=0.01" 0.01) (list "Xavier" 'xavier)
                 (list "He" 'he)))
    (let ((name (car entry))
          (init (cadr entry))
          (opt (make-instance 'sgd :lr 0.5)))
      (linalg:seed 42)
      (let ((net (make-multi-layer-net 784 '(30 30) 10 :weight-init-std init)))
        (format t "~a:~%" name)
        (dotimes (i *iters*)
          (let* ((batch-mask (linalg:choice *train-limit* *batch-size*))
                 (x-batch (linalg:take-rows x-train batch-mask))
                 (t-batch (linalg:take-rows t-train batch-mask))
                 (grads (net-gradient net x-batch t-batch)))
            (update opt (mln-params net) grads)
            (when (= (mod i 20) 0)
              (format t "  iter ~3d  loss ~,4f~%" i
                      (net-loss net x-batch t-batch)))))))))
