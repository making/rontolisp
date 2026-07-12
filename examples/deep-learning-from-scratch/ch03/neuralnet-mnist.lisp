;; ch03/neuralnet_mnist.py -- MNIST inference with the book's pretrained
;; 784-50-100-10 network (Deep Learning from Scratch).
;;
;; The weights come from sample-weight.bin (the book's sample_weight.pkl
;; re-exported by tools/export-sample-weight.py; already committed).
;; One image is classified at a time, exactly like the book; see
;; neuralnet-mnist-batch.lisp for the batch version. The book reaches
;; accuracy 0.9352 over the full 10000-image test set; the default here
;; evaluates the first *test-limit* images to keep the interpreter run
;; short (raise the knob for the full set).
;;
;; Data: run ../download-mnist.sh once, then from examples/deep-learning-from-scratch/:
;;   rontolisp ch03/neuralnet-mnist.lisp

(load "../common/functions.lisp")
(load "../dataset/mnist.lisp")

(defparameter *test-limit* 1000)

(defun predict (network x)
  ;; x: one flattened image (784-vector). vec.mat dot + bias broadcast,
  ;; sigmoid hidden layers, softmax output -- the book's forward pass.
  (let* ((a1 (linalg:add (linalg:dot x (getf network :w1)) (getf network :b1)))
         (z1 (sigmoid a1))
         (a2 (linalg:add (linalg:dot z1 (getf network :w2)) (getf network :b2)))
         (z2 (sigmoid a2))
         (a3 (linalg:add (linalg:dot z2 (getf network :w3)) (getf network :b3))))
    (softmax a3)))

(let ((x (mnist-load-images "dataset/t10k-images-idx3-ubyte" *test-limit*))
      (target (mnist-load-labels "dataset/t10k-labels-idx1-ubyte" *test-limit*))
      (network (load-sample-weight "ch03/sample-weight.bin"))
      (accuracy-cnt 0))
  (dotimes (i (car (linalg:shape x)))
    (let* ((y (predict network (linalg:flatten (linalg:take-rows x (linalg:from-list (list i))))))
           (p (linalg:argmax y)))
      (when (= p (aref target i))
        (setq accuracy-cnt (+ accuracy-cnt 1)))))
  (format t "Accuracy: ~a/~a~%" accuracy-cnt *test-limit*))
