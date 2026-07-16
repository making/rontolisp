;; ch08/half_float_network.py -- the trained deep CNN at reduced precision
;; (Deep Learning from Scratch).
;;
;; The book casts the network parameters and the test images from float64
;; to float16 and shows the test accuracy is unchanged. rontolisp's packed
;; reduced width is single-float (#f) -- literally half a double's 64
;; bits -- so the "half" cast here reloads the pretrained weights as
;; 'single-float through the width-polymorphic RLW1 loader (an f32 value
;; is exact in both widths) and copies the images into a packed #f array;
;; linalg preserves the #f width through every layer, so the whole forward
;; pass then runs single. Scaled down from the book's 10000 test images to
;; 1000. The two deep-convnet passes want acceleration: ~40 s on WASM
;; --simd, ~3 minutes under interpreter --simd or compiled to the JVM;
;; the plain interpreter runs the linalg defuns as scalar loops and takes
;; hours -- shrink *sampled* first. Output is byte-identical on every
;; backend (inference is exact IEEE arithmetic).
;;
;; Data: run ../download-mnist.sh once, then from examples/deep-learning-from-scratch/:
;;   rontolisp ch08/half-float-network.lisp --simd
;;   rontolisp ch08/half-float-network.lisp -o Prog.class && java -cp .:<rontolisp jar> Prog
;;   rontolisp ch08/half-float-network.lisp -o prog.wasm --simd --optimize && \
;;     wasmtime run -W gc --dir . prog.wasm
;;   rontolisp ch08/half-float-network.lisp -o comp.wasm --component --simd && \
;;     wasmtime run -W gc=y --dir . comp.wasm

(load "deep-convnet.lisp")
(load "../dataset/mnist.lisp")

(defparameter *sampled* 1000)   ; the book samples 10000
(defparameter *batch-size* 100)

(defun astype-single (a)
  ;; numpy's x.astype(np.float16) analog: copy into a packed #f array (the
  ;; store narrows each double to single precision).
  (let ((out (make-array (linalg:shape a) :element-type 'single-float
                         :initial-element 0.0)))
    (dotimes (i (array-total-size a))
      (setf (row-major-aref out i) (row-major-aref a i)))
    out))

(defun batched-accuracy (net x target)
  ;; net-accuracy-count over *batch-size* slices (one full-sample forward
  ;; would materialize ~900 MB of im2col unfolds).
  (let ((acc 0))
    (do ((start 0 (+ start *batch-size*)))
        ((>= start *sampled*) acc)
      (let ((idx (linalg:arange start (+ start *batch-size*))))
        (setq acc (+ acc (net-accuracy-count net
                                             (linalg:take-rows x idx)
                                             (linalg:take-rows target idx))))))))

(let* ((x-test (linalg:reshape
                (mnist-load-images "dataset/t10k-images-idx3-ubyte" *sampled*)
                (list *sampled* 1 28 28)))
       (t-test (mnist-load-labels "dataset/t10k-labels-idx1-ubyte" *sampled*))
       (net (make-deep-convnet :hidden-size 50 :output-size 10)))
  (net-load-params net "ch08/deep-convnet-params.bin" nil)
  (write-line "calculate accuracy (double-float) ...")
  (format t "~a/~a~%" (batched-accuracy net x-test t-test) *sampled*)
  ;; the float16 cast: reload the params as packed #f, cast the images
  (net-load-params net "ch08/deep-convnet-params.bin" 'single-float)
  (let ((x-single (astype-single x-test)))
    (write-line "calculate accuracy (single-float) ...")
    (format t "~a/~a~%" (batched-accuracy net x-single t-test) *sampled*)))
