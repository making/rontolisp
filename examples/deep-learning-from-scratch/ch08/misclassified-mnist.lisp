;; ch08/misclassified_mnist.py -- which test digits the trained deep CNN
;; still gets wrong (Deep Learning from Scratch).
;;
;; The pretrained weights (ch08/deep-convnet-params.bin, the book's
;; deep_convnet_params.pkl re-exported through tools/export-sample-weight.py)
;; load into the deep-convnet, test accuracy is evaluated in batches, and
;; each misclassified digit prints with its (label, inference) pair -- as
;; ASCII art (the ch03 mnist-show ramp) instead of the book's plot grid.
;; Scaled down from the book's full 10000-image test set to its own
;; commented-out suggestion of 1000 (the trained net misses exactly 20 of
;; those -- the numpy original misses the same 20). A deep-convnet forward
;; this size wants acceleration: ~19 s on WASM --simd, ~1.5 minutes under
;; interpreter --simd, ~2 minutes compiled to the JVM; the plain
;; interpreter runs the linalg defuns as scalar loops and takes hours --
;; shrink *sampled* first. Output is byte-identical on every backend
;; (inference is exact IEEE arithmetic; no exp/log).
;;
;; Data: run ../download-mnist.sh once, then from examples/deep-learning-from-scratch/:
;;   rontolisp ch08/misclassified-mnist.lisp --simd
;;   rontolisp ch08/misclassified-mnist.lisp -o Prog.class && java -cp .:<rontolisp jar> Prog
;;   rontolisp ch08/misclassified-mnist.lisp -o prog.wasm --simd --optimize && \
;;     wasmtime run --dir . prog.wasm
;;   rontolisp ch08/misclassified-mnist.lisp -o comp.wasm --component --simd && \
;;     wasmtime run --dir . comp.wasm

(load "deep-convnet.lisp")
(load "../dataset/mnist.lisp")

(defparameter *sampled* 1000) ; the book evaluates all 10000
(defparameter *batch-size* 100)
(defparameter *max-view* 20)

(defparameter *ramp* " .:-=+*#%@")

(defun render-digit (x i)
  ;; Row i of the rank-4 (n 1 28 28) test batch as ASCII art: each pixel
  ;; in [0,1] indexes the 10-step intensity ramp (ch03 mnist-show).
  (dotimes (y 28)
    (let ((line ""))
      (dotimes (col 28)
        (let* ((v (aref x i 0 y col)) (idx (min 9 (truncate (* v 10)))))
          (setq line (concatenate 'string line (subseq *ramp* idx (+ idx 1))))))
      (write-line line))))

(let* ((x-test
        (linalg:reshape
         (mnist-load-images "dataset/t10k-images-idx3-ubyte" *sampled*)
         (list *sampled* 1 28 28)))
       (t-test (mnist-load-labels "dataset/t10k-labels-idx1-ubyte" *sampled*))
       (net (make-deep-convnet :hidden-size 50 :output-size 10)))
  (net-load-params net "ch08/deep-convnet-params.bin" nil)
  (write-line "calculating test accuracy ...")
  (let ((classified
         (make-array *sampled*
                     :element-type 'double-float
                     :initial-element 0.0))
        (acc 0))
    (do ((start 0 (+ start *batch-size*)))
        ((>= start *sampled*))
      (let* ((idx (linalg:arange start (+ start *batch-size*)))
             (tx (linalg:take-rows x-test idx))
             (y (let ((*train-p* nil)) (predict net tx)))
             (yl (linalg:argmax y :axis 1)))
        (dotimes (k *batch-size*)
          (setf (aref classified (+ start k)) (aref yl k))
          (when (= (aref yl k) (aref t-test (+ start k)))
            (setq acc (+ acc 1))))))
    (format t "test accuracy: ~a/~a~%" acc *sampled*)
    (write-line "======= misclassified result =======")
    (let ((view 1))
      (dotimes (i *sampled*)
        (when (and (<= view *max-view*)
                   (/= (aref classified i) (aref t-test i)))
          (format t "view ~a: label ~a, inference ~a~%" view
                  (truncate (aref t-test i)) (truncate (aref classified i)))
          (render-digit x-test i)
          (setq view (+ view 1)))))))
