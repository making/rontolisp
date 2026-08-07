;; ch04/train_neuralnet.py -- mini-batch training of the two-layer net
;; (Deep Learning from Scratch).
;;
;; The book trains 10000 iterations (batch 100, lr 0.1) over the full
;; 60000-image set; the defaults here are scaled to *train-limit* 500
;; images, batch 16 and three epochs so the plain interpreter finishes in
;; a couple of minutes -- raise the knobs (and add --simd, whose
;; all-double output is byte-identical) for a longer run. The learning
;; rate is raised to 1.0: with 1/120 of the book's data the 0.1 sigmoid
;; net would still be in its flat warm-up phase when the run ends. The
;; gradient is the analytic tln-gradient, the same choice as the book's
;; active line (the numerical one is available but ~1000x slower). Loss
;; values pass through exp/log, so the last digits can differ on WASM;
;; accuracies print as exact correct/total counts.
;;
;; Data: run ../download-mnist.sh once, then from examples/deep-learning-from-scratch/:
;;   rontolisp ch04/train-neuralnet.lisp

(load "two-layer-net.lisp")
(load "../dataset/mnist.lisp")

(defparameter *train-limit* 500)
(defparameter *test-limit* 200)
(defparameter *batch-size* 16)
(defparameter *learning-rate* 1.0)
(defparameter *epochs* 3)

(linalg:seed 42)

(let* ((x-train
        (mnist-load-images "dataset/train-images-idx3-ubyte" *train-limit*))
       (t-train
        (mnist-load-labels "dataset/train-labels-idx1-ubyte" *train-limit* 0 t))
       (x-test
        (mnist-load-images "dataset/t10k-images-idx3-ubyte" *test-limit*))
       (t-test
        (mnist-load-labels "dataset/t10k-labels-idx1-ubyte" *test-limit* 0 t))
       (params (make-two-layer-net 784 50 10))
       (iter-per-epoch (floor *train-limit* *batch-size*))
       (iters-num (* *epochs* iter-per-epoch)))
  (dotimes (i iters-num)
    (let* ((batch-mask (linalg:choice *train-limit* *batch-size*))
           (x-batch (linalg:take-rows x-train batch-mask))
           (t-batch (linalg:take-rows t-train batch-mask))
           (grads (tln-gradient params x-batch t-batch)))
      (tln-update! params grads *learning-rate*)
      (when (= (mod i 10) 0)
        (format t "iter ~3d  loss ~,4f~%" i (tln-loss params x-batch t-batch)))
      (when (= (mod (+ i 1) iter-per-epoch) 0)
        (format t "train acc, test acc | ~a/~a, ~a/~a~%"
                (tln-accuracy-count params x-train t-train) *train-limit*
                (tln-accuracy-count params x-test t-test) *test-limit*)))))
