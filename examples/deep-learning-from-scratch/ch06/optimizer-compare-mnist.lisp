;; ch06/optimizer_compare_mnist.py -- the four optimizers on MNIST (Deep
;; Learning from Scratch).
;;
;; The book races SGD / Momentum / AdaGrad / Adam over a [100 100 100 100]
;; net for 2000 iterations and plots the smoothed losses; here each
;; optimizer trains a [30 30] net (fresh seeded weights per run) and the
;; batch loss is printed every 20 iterations. Adam and AdaGrad pull ahead
;; of plain SGD early, like the book's figure.
;;
;; Data: run ../download-mnist.sh once, then from examples/deep-learning-from-scratch/:
;;   rontolisp ch06/optimizer-compare-mnist.lisp   (add --simd to speed it up)

(load "../dataset/mnist.lisp")
(load "../common/multi-layer-net.lisp")
(load "../common/optimizer.lisp")

(defparameter *train-limit* 500)
(defparameter *batch-size* 16)
(defparameter *iters* 100)

(let ((x-train (mnist-load-images "dataset/train-images-idx3-ubyte" *train-limit*))
      (t-train (mnist-load-labels "dataset/train-labels-idx1-ubyte" *train-limit* 0 t)))
  ;; the book compares the optimizers at their class defaults
  ;; (SGD/Momentum/AdaGrad lr 0.01, Adam lr 0.001)
  (dolist (entry (list (list "SGD" (make-instance 'sgd))
                       (list "Momentum" (make-instance 'momentum))
                       (list "AdaGrad" (make-instance 'adagrad))
                       (list "Adam" (make-instance 'adam))))
    (let ((name (car entry))
          (opt (cadr entry)))
      (linalg:seed 42)
      (let ((net (make-multi-layer-net 784 '(30 30) 10)))
        (format t "~a:~%" name)
        (dotimes (i *iters*)
          (let* ((batch-mask (linalg:choice *train-limit* *batch-size*))
                 (x-batch (linalg:take-rows x-train batch-mask))
                 (t-batch (linalg:take-rows t-train batch-mask))
                 (grads (net-gradient net x-batch t-batch)))
            (update opt (mln-params net) grads)
            (when (= (mod i 20) 0)
              (format t "  iter ~3d  loss ~,4f~%"
                      i (net-loss net x-batch t-batch)))))
        (let ((mask (linalg:arange *train-limit*)))
          (format t "  final train acc: ~a/~a~%"
                  (net-accuracy-count net
                                      (linalg:take-rows x-train mask)
                                      (linalg:take-rows t-train mask))
                  *train-limit*))))))
