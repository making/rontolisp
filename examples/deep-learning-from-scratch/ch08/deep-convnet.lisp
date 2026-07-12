;; ch08/deep_convnet.py -- the deep CNN (Deep Learning from Scratch).
;; A library file loaded by train-deepnet.lisp.
;;
;; The 16-16 / 32-32 / 64-64 convolution pyramid: (Conv Relu Conv Relu
;; Pool) x 3 -> Affine Relu Dropout -> Affine Dropout -> SoftmaxWithLoss,
;; with He-scaled initialization over each layer's incoming-connection
;; count (the book's pre_node_nums). Geometry is fixed to 28x28 inputs
;; like the book: pad 1 keeps each conv shape-preserving except conv4's
;; pad 2 (14 -> 16), so the three 2x2 pools land on 64 channels of 4x4.
;; The eight parameter layers (six conv + two affine) share their arrays
;; with the params hash under "W1".."W8"/"b1".."b8"; the layer-dw/db
;; generics fold their cached gradients back per key.

(load "../common/layers.lisp")

(defclass deep-convnet ()
  ((params :initarg :params :accessor dcn-params)
   (layers :initarg :layers :accessor dcn-layers)
   (grad-layers :initarg :grad-layers :accessor dcn-grad-layers)
   (last-layer :initarg :last-layer :accessor dcn-last-layer)))

(defparameter *dcn-keys*
  '("W1" "b1" "W2" "b2" "W3" "b3" "W4" "b4"
    "W5" "b5" "W6" "b6" "W7" "b7" "W8" "b8"))

(defun make-deep-convnet (&key (hidden-size 50) (output-size 10))
  ;; conv-specs: (filter-num prev-channels pad) per conv layer; filter
  ;; size 3 and stride 1 throughout. pre-node-nums are the incoming
  ;; connections of W1..W8 (prev-channels * 3 * 3 for a conv, the input
  ;; width for an affine); He init scales each randn by sqrt(2/n).
  (let ((conv-specs '((16 1 1) (16 16 1) (32 16 1) (32 32 2) (64 32 1) (64 64 1)))
        (pre-node-nums '(9 144 144 288 288 576 1024 50))
        (pool-flat (* 64 4 4))
        (params (make-hash-table :test 'equal)))
    (do ((idx 1 (+ idx 1))
         (specs conv-specs (cdr specs))
         (fans pre-node-nums (cdr fans)))
        ((null specs))
      (let* ((spec (car specs))
             (fn (car spec))
             (prev (car (cdr spec))))
        (setf (gethash (format nil "W~a" idx) params)
              (linalg:mul (sqrt (/ 2.0 (car fans)))
                          (linalg:randn (list fn prev 3 3))))
        (setf (gethash (format nil "b~a" idx) params) (linalg:zeros fn))))
    (setf (gethash "W7" params)
          (linalg:mul (sqrt (/ 2.0 1024))
                      (linalg:randn (list pool-flat hidden-size))))
    (setf (gethash "b7" params) (linalg:zeros hidden-size))
    (setf (gethash "W8" params)
          (linalg:mul (sqrt (/ 2.0 50))
                      (linalg:randn (list hidden-size output-size))))
    (setf (gethash "b8" params) (linalg:zeros output-size))
    (let ((convs nil))
      (do ((idx 1 (+ idx 1))
           (specs conv-specs (cdr specs)))
          ((null specs))
        (setq convs
              (cons (make-instance 'convolution
                                   :w (gethash (format nil "W~a" idx) params)
                                   :b (gethash (format nil "b~a" idx) params)
                                   :stride 1 :pad (nth 2 (car specs)))
                    convs)))
      (let* ((convs (reverse convs))
             (affine1 (make-instance 'affine :w (gethash "W7" params)
                                     :b (gethash "b7" params)))
             (affine2 (make-instance 'affine :w (gethash "W8" params)
                                     :b (gethash "b8" params))))
        (make-instance 'deep-convnet
                       :params params
                       :layers (list (nth 0 convs)
                                     (make-instance 'relu-layer)
                                     (nth 1 convs)
                                     (make-instance 'relu-layer)
                                     (make-instance 'pooling :pool-h 2 :pool-w 2
                                                    :stride 2)
                                     (nth 2 convs)
                                     (make-instance 'relu-layer)
                                     (nth 3 convs)
                                     (make-instance 'relu-layer)
                                     (make-instance 'pooling :pool-h 2 :pool-w 2
                                                    :stride 2)
                                     (nth 4 convs)
                                     (make-instance 'relu-layer)
                                     (nth 5 convs)
                                     (make-instance 'relu-layer)
                                     (make-instance 'pooling :pool-h 2 :pool-w 2
                                                    :stride 2)
                                     affine1
                                     (make-instance 'relu-layer)
                                     (make-instance 'dropout :ratio 0.5)
                                     affine2
                                     (make-instance 'dropout :ratio 0.5))
                       :grad-layers (append convs (list affine1 affine2))
                       :last-layer (make-instance 'softmax-with-loss))))))

;; The per-parameter-layer gradient accessors: conv and affine cache their
;; dW/db in differently named slots, so one generic pair folds both.

(defgeneric layer-dw (layer))

(defgeneric layer-db (layer))

(defmethod layer-dw ((layer convolution))
  (conv-dw layer))

(defmethod layer-dw ((layer affine))
  (affine-dw layer))

(defmethod layer-db ((layer convolution))
  (conv-db layer))

(defmethod layer-db ((layer affine))
  (affine-db layer))

(defgeneric predict (net x))

(defgeneric net-loss (net x target))

(defgeneric net-gradient (net x target))

(defgeneric net-accuracy-count (net x target))

(defmethod predict ((net deep-convnet) x)
  (let ((out x))
    (dolist (layer (dcn-layers net))
      (setq out (forward layer out)))
    out))

(defmethod net-loss ((net deep-convnet) x target)
  (loss-forward (dcn-last-layer net) (predict net x) target))

(defmethod net-accuracy-count ((net deep-convnet) x target)
  ;; Correctly classified batch rows, with dropout switched to its
  ;; evaluation behavior; target may be one-hot or labels.
  (let* ((y (let ((*train-p* nil))
              (predict net x)))
         (yl (linalg:argmax y 1))
         (tl (if (= (linalg:ndim target) 1)
                 target
                 (linalg:argmax target 1))))
    (truncate (linalg:sum (linalg:equal yl tl)))))

(defmethod net-gradient ((net deep-convnet) x target)
  ;; forward for the caches, then fold backward over the reversed layers;
  ;; each parameter layer leaves dW/db behind, collected as W1..W8.
  (net-loss net x target)
  (let ((dout (loss-backward (dcn-last-layer net))))
    (dolist (layer (reverse (dcn-layers net)))
      (setq dout (backward layer dout))))
  (let ((grads (make-hash-table :test 'equal)))
    (do ((idx 1 (+ idx 1))
         (layers (dcn-grad-layers net) (cdr layers)))
        ((null layers) grads)
      (setf (gethash (format nil "W~a" idx) grads) (layer-dw (car layers)))
      (setf (gethash (format nil "b~a" idx) grads) (layer-db (car layers))))))
