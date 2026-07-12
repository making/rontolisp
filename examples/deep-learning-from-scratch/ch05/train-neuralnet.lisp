;; ch05/train_neuralnet.py -- mini-batch training through the layer-based
;; network (Deep Learning from Scratch).
;;
;; The same training loop as ch04's, but the gradient now comes from
;; backpropagation through the Affine -> Relu -> Affine layer objects --
;; the payoff of the chapter. Scaled like ch04 (500 train images, batch
;; 16, three epochs; lr 0.3 instead of the book's 0.1, tuned for the small
;; set -- the ReLU net needs less than ch04's slow-starting sigmoid took);
;; raise the knobs for a longer run, or add --simd for the same output
;; faster.
;;
;; Data: run ../download-mnist.sh once, then from examples/deep-learning-from-scratch/:
;;   rontolisp ch05/train-neuralnet.lisp

(load "two-layer-net.lisp")
(load "../dataset/mnist.lisp")

(defparameter *train-limit* 500)
(defparameter *test-limit* 200)
(defparameter *batch-size* 16)
(defparameter *learning-rate* 0.3)
(defparameter *epochs* 3)

(linalg:seed 42)

(defun update-params! (params grads lr)
  ;; params[key] -= lr * grads[key], IN PLACE (the layers alias the same
  ;; arrays).
  (dolist (key *tln-keys*)
    (let ((p (gethash key params))
          (g (gethash key grads)))
      (dotimes (k (linalg:size p))
        (setf (row-major-aref p k)
              (- (row-major-aref p k) (* lr (row-major-aref g k))))))))

(let* ((x-train (mnist-load-images "dataset/train-images-idx3-ubyte" *train-limit*))
       (t-train (mnist-load-labels "dataset/train-labels-idx1-ubyte" *train-limit* 0 t))
       (x-test (mnist-load-images "dataset/t10k-images-idx3-ubyte" *test-limit*))
       (t-test (mnist-load-labels "dataset/t10k-labels-idx1-ubyte" *test-limit* 0 t))
       (net (make-two-layer-net 784 50 10))
       (iter-per-epoch (floor *train-limit* *batch-size*))
       (iters-num (* *epochs* iter-per-epoch)))
  (dotimes (i iters-num)
    (let* ((batch-mask (linalg:choice *train-limit* *batch-size*))
           (x-batch (linalg:take-rows x-train batch-mask))
           (t-batch (linalg:take-rows t-train batch-mask))
           (grads (net-gradient net x-batch t-batch)))
      (update-params! (net-params net) grads *learning-rate*)
      (when (= (mod i 10) 0)
        (format t "iter ~3d  loss ~,4f~%" i (net-loss net x-batch t-batch)))
      (when (= (mod (+ i 1) iter-per-epoch) 0)
        (format t "train acc, test acc | ~a/~a, ~a/~a~%"
                (net-accuracy-count net x-train t-train) *train-limit*
                (net-accuracy-count net x-test t-test) *test-limit*)))))
