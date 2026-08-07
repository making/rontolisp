;; ch06/overfit_dropout.py -- taming overfitting with Dropout (Deep
;; Learning from Scratch).
;;
;; The same 300-image overfitting setup as overfit-weight-decay.lisp, but
;; the cure is Dropout: randomly silencing units during training keeps
;; train accuracy from saturating and narrows the train/test gap. Uses
;; the shared trainer (common/trainer.lisp), like the book's script uses
;; its Trainer class. The book runs dropout 0.2 with lr 0.01 for 301
;; epochs on a [100]*6 net; here the same ratio with lr 0.2 for 20 epochs
;; on [20 20] -- dropout keeps train accuracy off the 100% ceiling.
;;
;; Data: run ../download-mnist.sh once, then from examples/deep-learning-from-scratch/:
;;   rontolisp ch06/overfit-dropout.lisp   (add --simd to speed it up)

(load "../dataset/mnist.lisp")
(load "../common/multi-layer-net-extend.lisp")
(load "../common/trainer.lisp")

(defparameter *train-limit* 300)
(defparameter *test-limit* 300)

(let ((x-train
       (mnist-load-images "dataset/train-images-idx3-ubyte" *train-limit*))
      (t-train
       (mnist-load-labels "dataset/train-labels-idx1-ubyte" *train-limit* 0 t))
      (x-test (mnist-load-images "dataset/t10k-images-idx3-ubyte" *test-limit*))
      (t-test
       (mnist-load-labels "dataset/t10k-labels-idx1-ubyte" *test-limit* 0 t)))
  (format t "without dropout (overfits):~%")
  (linalg:seed 42)
  (let ((net (make-multi-layer-net-extend 784 '(20 20) 10)))
    (train net (mlne-params net) x-train t-train x-test t-test
           :epochs 20
           :mini-batch-size 32
           :optimizer (make-instance 'sgd :lr 0.2)))
  (format t "with dropout 0.2:~%")
  (linalg:seed 42)
  (let ((net
         (make-multi-layer-net-extend 784 '(20 20) 10
                                      :use-dropout t
                                      :dropout-ratio 0.2)))
    (train net (mlne-params net) x-train t-train x-test t-test
           :epochs 20
           :mini-batch-size 32
           :optimizer (make-instance 'sgd :lr 0.2))))
