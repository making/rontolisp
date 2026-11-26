;; ch07/train_convnet.py -- training the SimpleConvNet on MNIST (Deep
;; Learning from Scratch).
;;
;; Conv -> Relu -> Pool -> Affine -> Relu -> Affine with Adam, through the
;; common trainer. Scaled down hard from the book (100 train images
;; instead of 5000, batch 10, one epoch, accuracy evaluated on the full
;; small subsets): a CNN forward is ~100x an MLP's, so the plain
;; interpreter takes a few minutes here -- add --simd for the same output
;; much faster (im2col turns the convolution into linalg:matmul, which is
;; --simd-intercepted), or compile to the JVM. Raise the knobs toward the
;; book's 5000/batch 100/20 epochs accordingly.
;;
;; Data: run ../download-mnist.sh once, then from examples/deep-learning-from-scratch/:
;;   rontolisp ch07/train-convnet.lisp

(load "simple-convnet.lisp")
(load "../common/trainer.lisp")
(load "../dataset/mnist.lisp")

(defparameter *train-limit* 100)
(defparameter *test-limit* 50)
(defparameter *batch-size* 10)
(defparameter *epochs* 1)

(linalg:seed 42)

(let* ((x-train
        (linalg:reshape
         (mnist-load-images "dataset/train-images-idx3-ubyte" *train-limit*)
         (list *train-limit* 1 28 28)))
       (t-train
        (mnist-load-labels "dataset/train-labels-idx1-ubyte" *train-limit* 0 t))
       (x-test
        (linalg:reshape
         (mnist-load-images "dataset/t10k-images-idx3-ubyte" *test-limit*)
         (list *test-limit* 1 28 28)))
       (t-test
        (mnist-load-labels "dataset/t10k-labels-idx1-ubyte" *test-limit* 0 t))
       (net
        (make-simple-convnet :input-dim '(1 28 28)
                             :filter-num 30
                             :filter-size 5
                             :filter-pad 0
                             :filter-stride 1
                             :hidden-size 100
                             :output-size 10
                             :weight-init-std 0.01)))
  (train net (scn-params net) x-train t-train x-test t-test
         :epochs *epochs*
         :mini-batch-size *batch-size*
         :optimizer (make-instance 'adam :lr 0.001)))
