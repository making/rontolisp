;; ch05/two_layer_net.py -- the layer-based two-layer network (Deep
;; Learning from Scratch). A library file loaded by gradient-check.lisp
;; and train-neuralnet.lisp.
;;
;; The ch04 net rewritten over the CLOS layers of common/layers.lisp: the
;; network is Affine -> Relu -> Affine chained through an ordered layer
;; LIST (the book's OrderedDict), plus SoftmaxWithLoss at the end. The
;; params hash holds the SAME array objects the affine layers do, so an
;; in-place optimizer update is immediately visible to the layers -- the
;; book's aliasing contract. Backprop is one fold over the reversed list.

(load "../common/layers.lisp")
(load "../common/gradient.lisp")

(defclass two-layer-net ()
  ((params :initarg :params :accessor net-params)
   (layers :initarg :layers :accessor net-layers)
   (affine1 :initarg :affine1 :accessor net-affine1)
   (affine2 :initarg :affine2 :accessor net-affine2)
   (last-layer :initarg :last-layer :accessor net-last-layer)))

(defparameter *tln-keys* '("W1" "b1" "W2" "b2"))

(defun make-two-layer-net
    (input-size hidden-size output-size &optional (weight-init-std 0.01))
  (let ((params (make-hash-table :test 'equal)))
    (setf (gethash "W1" params)
     (linalg:mul weight-init-std (linalg:randn (list input-size hidden-size))))
    (setf (gethash "b1" params) (linalg:zeros hidden-size))
    (setf (gethash "W2" params)
     (linalg:mul weight-init-std (linalg:randn (list hidden-size output-size))))
    (setf (gethash "b2" params) (linalg:zeros output-size))
    (let ((affine1
           (make-instance 'affine
                          :w (gethash "W1" params)
                          :b (gethash "b1" params)))
          (affine2
           (make-instance 'affine
                          :w (gethash "W2" params)
                          :b (gethash "b2" params))))
      (make-instance 'two-layer-net
                     :params params
                     :layers (list affine1 (make-instance 'relu-layer) affine2)
                     :affine1 affine1
                     :affine2 affine2
                     :last-layer (make-instance 'softmax-with-loss)))))

(defgeneric predict (net x))

(defgeneric net-loss (net x target))

(defgeneric net-gradient (net x target))

(defgeneric net-numerical-gradient (net x target))

(defgeneric net-accuracy-count (net x target))

(defmethod predict ((net two-layer-net) x)
  (let ((out x))
    (dolist (layer (net-layers net)) (setq out (forward layer out)))
    out))

(defmethod net-loss ((net two-layer-net) x target)
  (loss-forward (net-last-layer net) (predict net x) target))

(defmethod net-accuracy-count ((net two-layer-net) x target)
  ;; Correctly classified rows; target may be one-hot or a label vector.
  (let ((y (linalg:argmax (predict net x) 1))
        (tl (if (= (linalg:ndim target) 1) target (linalg:argmax target 1))))
    (truncate (linalg:sum (linalg:equal y tl)))))

(defmethod net-gradient ((net two-layer-net) x target)
  ;; forward for the caches, then fold backward over the reversed layers;
  ;; the affine layers leave dW/db behind.
  (net-loss net x target)
  (let ((dout (loss-backward (net-last-layer net))))
    (dolist (layer (reverse (net-layers net)))
      (setq dout (backward layer dout))))
  (let ((grads (make-hash-table :test 'equal)))
    (setf (gethash "W1" grads) (affine-dw (net-affine1 net)))
    (setf (gethash "b1" grads) (affine-db (net-affine1 net)))
    (setf (gethash "W2" grads) (affine-dw (net-affine2 net)))
    (setf (gethash "b2" grads) (affine-db (net-affine2 net)))
    grads))

(defmethod net-numerical-gradient ((net two-layer-net) x target)
  ;; Central differences over every parameter element (slow; the
  ;; gradient-check yardstick).
  (let ((grads (make-hash-table :test 'equal))
        (loss-w (lambda (w) (net-loss net x target))))
    (dolist (key *tln-keys*)
      (setf (gethash key grads)
            (numerical-gradient loss-w (gethash key (net-params net)))))
    grads))
