;; ch07/simple_convnet.py -- the first CNN (Deep Learning from Scratch).
;; A library file loaded by gradient-check.lisp and train-convnet.lisp.
;;
;; Conv -> Relu -> Pool(2x2) -> Affine -> Relu -> Affine, plus
;; SoftmaxWithLoss, over the CLOS layers of common/layers.lisp (the ch07
;; Convolution/Pooling included). As in ch05, the params hash holds the
;; SAME array objects the layers do, so an in-place optimizer update is
;; immediately visible -- the book's aliasing contract. Inputs are rank-4
;; NCHW batches (reshape the flat MNIST rows to (n 1 28 28) first).

(load "../common/layers.lisp")
(load "../common/gradient.lisp")
(require "rlw1" "../dataset/rlw1.lisp")

(defclass simple-convnet ()
  ((params :initarg :params :accessor scn-params)
   (layers :initarg :layers :accessor scn-layers)
   (conv1 :initarg :conv1 :accessor scn-conv1)
   (affine1 :initarg :affine1 :accessor scn-affine1)
   (affine2 :initarg :affine2 :accessor scn-affine2)
   (last-layer :initarg :last-layer :accessor scn-last-layer)))

(defparameter *scn-keys* '("W1" "b1" "W2" "b2" "W3" "b3"))

(defun make-simple-convnet (&key (input-dim '(1 28 28)) (filter-num 30)
                                 (filter-size 5) (filter-pad 0)
                                 (filter-stride 1) (hidden-size 100)
                                 (output-size 10) (weight-init-std 0.01))
  (let* ((channels (car input-dim))
         (input-size (car (cdr input-dim)))
         (conv-output-size
          (+ 1
             (floor (- (+ input-size (* 2 filter-pad)) filter-size)
                    filter-stride)))
         (pool-output-size
          (* filter-num (floor conv-output-size 2) (floor conv-output-size 2)))
         (params (make-hash-table :test 'equal)))
    (setf (gethash "W1" params)
          (linalg:mul weight-init-std
           (linalg:randn (list filter-num channels filter-size filter-size))))
    (setf (gethash "b1" params) (linalg:zeros filter-num))
    (setf (gethash "W2" params)
          (linalg:mul weight-init-std
                      (linalg:randn (list pool-output-size hidden-size))))
    (setf (gethash "b2" params) (linalg:zeros hidden-size))
    (setf (gethash "W3" params)
     (linalg:mul weight-init-std (linalg:randn (list hidden-size output-size))))
    (setf (gethash "b3" params) (linalg:zeros output-size))
    (let ((conv1
           (make-instance 'convolution
                          :w (gethash "W1" params)
                          :b (gethash "b1" params)
                          :stride filter-stride
                          :pad filter-pad))
          (affine1
           (make-instance 'affine
                          :w (gethash "W2" params)
                          :b (gethash "b2" params)))
          (affine2
           (make-instance 'affine
                          :w (gethash "W3" params)
                          :b (gethash "b3" params))))
      (make-instance 'simple-convnet
                     :params params
                     :layers (list conv1 (make-instance 'relu-layer)
                                   (make-instance 'pooling
                                                  :pool-h 2
                                                  :pool-w 2
                                                  :stride 2) affine1
                                   (make-instance 'relu-layer) affine2)
                     :conv1 conv1
                     :affine1 affine1
                     :affine2 affine2
                     :last-layer (make-instance 'softmax-with-loss)))))

(defgeneric predict (net x))

(defgeneric net-loss (net x target))

(defgeneric net-gradient (net x target))

(defgeneric net-numerical-gradient (net x target))

(defgeneric net-accuracy-count (net x target))

(defmethod predict ((net simple-convnet) x)
  (let ((out x))
    (dolist (layer (scn-layers net)) (setq out (forward layer out)))
    out))

(defmethod net-loss ((net simple-convnet) x target)
  (loss-forward (scn-last-layer net) (predict net x) target))

(defmethod net-accuracy-count ((net simple-convnet) x target)
  ;; Correctly classified batch rows; target may be one-hot or labels.
  (let ((y (linalg:argmax (predict net x) 1))
        (tl (if (= (linalg:ndim target) 1) target (linalg:argmax target 1))))
    (truncate (linalg:sum (linalg:equal y tl)))))

(defmethod net-gradient ((net simple-convnet) x target)
  ;; forward for the caches, then fold backward over the reversed layers;
  ;; conv1 leaves dW/db behind like the affine layers.
  (net-loss net x target)
  (let ((dout (loss-backward (scn-last-layer net))))
    (dolist (layer (reverse (scn-layers net)))
      (setq dout (backward layer dout))))
  (let ((grads (make-hash-table :test 'equal)))
    (setf (gethash "W1" grads) (conv-dw (scn-conv1 net)))
    (setf (gethash "b1" grads) (conv-db (scn-conv1 net)))
    (setf (gethash "W2" grads) (affine-dw (scn-affine1 net)))
    (setf (gethash "b2" grads) (affine-db (scn-affine1 net)))
    (setf (gethash "W3" grads) (affine-dw (scn-affine2 net)))
    (setf (gethash "b3" grads) (affine-db (scn-affine2 net)))
    grads))

(defmethod net-numerical-gradient ((net simple-convnet) x target)
  ;; Central differences over every parameter element (slow; the
  ;; gradient-check yardstick).
  (let ((grads (make-hash-table :test 'equal))
        (loss-w (lambda (w) (net-loss net x target))))
    (dolist (key *scn-keys*)
      (setf (gethash key grads)
            (numerical-gradient loss-w (gethash key (scn-params net)))))
    grads))

(defgeneric net-load-params (net path element-type))

(defmethod net-load-params ((net simple-convnet) path element-type)
  ;; The book's load_params over an RLW1 export of params.pkl (W1 b1 W2 b2
  ;; W3 b3): replace the hash entries, then re-point the parameter layers'
  ;; shared arrays. element-type nil = double, 'single-float = packed #f
  ;; (linalg is width-polymorphic, so the whole net then runs single).
  (let ((params (scn-params net)))
    (do ((keys *scn-keys* (cdr keys))
         (arrays (load-rlw1 path element-type) (cdr arrays)))
        ((null keys))
      (setf (gethash (car keys) params) (car arrays)))
    (layer-set-params (scn-conv1 net) (gethash "W1" params)
                      (gethash "b1" params))
    (layer-set-params (scn-affine1 net) (gethash "W2" params)
                      (gethash "b2" params))
    (layer-set-params (scn-affine2 net) (gethash "W3" params)
                      (gethash "b3" params))
    net))
