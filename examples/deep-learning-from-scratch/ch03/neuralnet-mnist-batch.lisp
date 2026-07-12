;; ch03/neuralnet_mnist_batch.py -- batch MNIST inference (Deep Learning
;; from Scratch).
;;
;; The same pretrained network as neuralnet-mnist.lisp, but 100 images per
;; forward pass: the (100 x 784) batch flows through mat.mat products and
;; the bias broadcasts over the rows, then argmax(axis=1) classifies the
;; whole batch at once -- the book's point about vectorized inference.
;;
;; Data: run ../download-mnist.sh once, then from examples/deep-learning-from-scratch/:
;;   rontolisp ch03/neuralnet-mnist-batch.lisp

(load "../common/functions.lisp")
(load "../dataset/mnist.lisp")

(defparameter *test-limit* 1000)
(defparameter *batch-size* 100)

(defun predict (network x)
  ;; x: an (N x 784) batch.
  (let* ((a1 (linalg:add (linalg:matmul x (getf network :w1)) (getf network :b1)))
         (z1 (sigmoid a1))
         (a2 (linalg:add (linalg:matmul z1 (getf network :w2)) (getf network :b2)))
         (z2 (sigmoid a2))
         (a3 (linalg:add (linalg:matmul z2 (getf network :w3)) (getf network :b3))))
    (softmax a3)))

(let ((x (mnist-load-images "dataset/t10k-images-idx3-ubyte" *test-limit*))
      (target (mnist-load-labels "dataset/t10k-labels-idx1-ubyte" *test-limit*))
      (network (load-sample-weight "ch03/sample-weight.bin"))
      (accuracy-cnt 0))
  (do ((i 0 (+ i *batch-size*)))
      ((>= i *test-limit*))
    (let* ((idx (linalg:arange i (+ i *batch-size*)))
           (x-batch (linalg:take-rows x idx))
           (t-batch (linalg:take-rows target idx))
           (p (linalg:argmax (predict network x-batch) 1)))
      (setq accuracy-cnt
            (+ accuracy-cnt (truncate (linalg:sum (linalg:equal p t-batch)))))))
  (format t "Accuracy: ~a/~a~%" accuracy-cnt *test-limit*))
