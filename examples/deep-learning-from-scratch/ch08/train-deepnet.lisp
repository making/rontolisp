;; ch08/train_deepnet.py -- training the deep CNN on MNIST (Deep Learning
;; from Scratch).
;;
;; The 8-parameter-layer pyramid of deep-convnet.lisp with Adam, through
;; the common trainer. The book reaches ~99.4% test accuracy over 20
;; epochs on the full 60000 images -- hours even in numpy; this port's
;; defaults are a smoke-scale run (30 train images, batch 5, one epoch)
;; that exercises every layer's forward/backward: ~12 minutes on the
;; plain interpreter, a minute and a half under --simd, seconds on the
;; JVM. Raise the knobs toward the book's settings if you have the
;; patience.
;;
;; Data: run ../download-mnist.sh once, then from examples/deep-learning-from-scratch/:
;;   rontolisp ch08/train-deepnet.lisp

(load "deep-convnet.lisp")
(load "../common/trainer.lisp")
(load "../dataset/mnist.lisp")

(defparameter *train-limit* 30)
(defparameter *test-limit* 10)
(defparameter *batch-size* 5)
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
       (net (make-deep-convnet :hidden-size 50 :output-size 10)))
  (train net (dcn-params net) x-train t-train x-test t-test
         :epochs *epochs*
         :mini-batch-size *batch-size*
         :optimizer (make-instance 'adam :lr 0.001)))
