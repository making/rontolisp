;; ch03/mnist_show.py -- display one MNIST digit (Deep Learning from Scratch).
;;
;; The book renders the first training image with PIL; here the 28x28 pixels
;; become an ASCII-art intensity ramp instead. Output is byte-identical on
;; every backend (no floats are printed).
;;
;; Data: run ../download-mnist.sh once, then from examples/deep-learning-from-scratch/:
;;   rontolisp ch03/mnist-show.lisp
;;   rontolisp ch03/mnist-show.lisp -o Prog.class && java -cp .:<rontolisp jar> Prog
;;   rontolisp ch03/mnist-show.lisp -o prog.wasm --optimize && wasmtime run -W gc --dir . prog.wasm
;;   rontolisp ch03/mnist-show.lisp -o comp.wasm --component && \
;;     wasmtime run -W gc=y -W component-model-more-async-builtins=y --dir . comp.wasm

(load "../dataset/mnist.lisp")

(defparameter *ramp* " .:-=+*#%@")

(defun render-image (img row)
  ;; One 28x28 image (row ROW of the (n x 784) matrix) as ASCII art: each
  ;; pixel in [0,1] indexes the 10-step intensity ramp.
  (dotimes (y 28)
    (let ((line ""))
      (dotimes (x 28)
        (let* ((v (aref img row (+ (* y 28) x)))
               (i (min 9 (truncate (* v 10)))))
          (setq line (concatenate 'string line (subseq *ramp* i (+ i 1))))))
      (write-line line))))

(let ((img (mnist-load-images "dataset/train-images-idx3-ubyte" 1))
      (lab (mnist-load-labels "dataset/train-labels-idx1-ubyte" 1)))
  (format t "label: ~a~%" (truncate (aref lab 0)))
  (format t "shape: ~a~%" (linalg:shape img))
  (render-image img 0))
