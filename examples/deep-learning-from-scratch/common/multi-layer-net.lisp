;; common/multi_layer_net.py -- the fully-connected multi-layer network
;; (Deep Learning from Scratch). A library file for the ch06 scripts.
;;
;; An arbitrary stack of Affine -> activation layers with He/Xavier weight
;; initialization and L2 weight decay, over the CLOS layers of
;; common/layers.lisp. params/grads are string-keyed hash tables
;; ("W1".."Wn+1", "b1".."bn+1"); mln-keys keeps the deterministic key
;; order (maphash order is unspecified). Do not load this file and
;; multi-layer-net-extend.lisp into the same program -- each pulls in
;; layers.lisp itself.

(load "layers.lisp")
(load "gradient.lisp")

(defclass multi-layer-net ()
  ((params :initarg :params :accessor mln-params)
   (layers :initarg :layers :accessor mln-layers)
   (affines :initarg :affines :accessor mln-affines) ; ordered, 1..n+1
   (keys :initarg :keys :accessor mln-keys)          ; ("W1" "b1" ...)
   (weight-decay-lambda :initarg :weight-decay-lambda
                        :accessor mln-weight-decay-lambda)
   (last-layer :initarg :last-layer :accessor mln-last-layer)))

(defun %init-scale (weight-init-std prev-size)
  ;; A number is used as-is; 'relu / 'he give the He scale sqrt(2/n),
  ;; 'sigmoid / 'xavier the Xavier scale sqrt(1/n).
  (cond ((numberp weight-init-std) weight-init-std)
        ((member weight-init-std '(relu he)) (sqrt (/ 2.0 prev-size)))
        ((member weight-init-std '(sigmoid xavier)) (sqrt (/ 1.0 prev-size)))
        (t (error "unknown weight-init-std"))))

(defun make-multi-layer-net (input-size hidden-size-list output-size &key
                                        (activation 'relu)
                                        (weight-init-std 'relu)
                                        (weight-decay-lambda 0))
  (let* ((all-sizes
          (append (list input-size) hidden-size-list (list output-size)))
         (n-hidden (length hidden-size-list))
         (params (make-hash-table :test 'equal))
         (keys nil)
         (affines nil)
         (layer-list nil))
    ;; weights: W_idx is (all-sizes[idx-1] x all-sizes[idx])
    (do ((idx 1 (+ idx 1)) (sizes all-sizes (cdr sizes)))
        ((null (cdr sizes)))
      (let ((prev (car sizes))
            (next (cadr sizes))
            (wk (format nil "W~a" idx))
            (bk (format nil "b~a" idx)))
        (setf (gethash wk params)
              (linalg:mul (%init-scale weight-init-std prev)
                          (linalg:randn (list prev next))))
        (setf (gethash bk params) (linalg:zeros next))
        (setq keys (cons bk (cons wk keys)))))
    (setq keys (reverse keys))
    ;; layers: Affine -> activation per hidden layer, final Affine
    (do ((idx 1 (+ idx 1)))
        ((> idx (+ n-hidden 1)))
      (let ((aff
             (make-instance 'affine
                            :w (gethash (format nil "W~a" idx) params)
                            :b (gethash (format nil "b~a" idx) params))))
        (setq affines (cons aff affines))
        (setq layer-list (cons aff layer-list))
        (when (<= idx n-hidden)
          (setq layer-list
                (cons (if (eq activation 'sigmoid)
                          (make-instance 'sigmoid-layer)
                          (make-instance 'relu-layer)) layer-list)))))
    (make-instance 'multi-layer-net
                   :params params
                   :layers (reverse layer-list)
                   :affines (reverse affines)
                   :keys keys
                   :weight-decay-lambda weight-decay-lambda
                   :last-layer (make-instance 'softmax-with-loss))))

(defgeneric predict (net x))

(defgeneric net-loss (net x target))

(defgeneric net-gradient (net x target))

(defgeneric net-numerical-gradient (net x target))

(defgeneric net-accuracy-count (net x target))

(defmethod predict ((net multi-layer-net) x)
  (let ((out x))
    (dolist (layer (mln-layers net)) (setq out (forward layer out)))
    out))

(defmethod net-loss ((net multi-layer-net) x target)
  ;; cross-entropy + the 0.5 * lambda * sum(W^2) L2 penalty per weight.
  (let ((decay 0) (lam (mln-weight-decay-lambda net)) (idx 0))
    (dolist (aff (mln-affines net))
      (setq idx (+ idx 1))
      (setq decay
            (+ decay (* 0.5 lam (linalg:sum (linalg:square (affine-w aff)))))))
    (+ (loss-forward (mln-last-layer net) (predict net x) target) decay)))

(defmethod net-accuracy-count ((net multi-layer-net) x target)
  (let* ((y (let ((*train-p* nil)) (predict net x)))
         (yl (linalg:argmax y :axis 1))
         (tl
          (if (= (linalg:ndim target) 1)
              target
              (linalg:argmax target :axis 1))))
    (truncate (linalg:sum (linalg:equal yl tl)))))

(defmethod net-gradient ((net multi-layer-net) x target)
  ;; Backprop; grads["Wi"] = dW_i + lambda * W_i (the weight-decay term).
  (net-loss net x target)
  (let ((dout (loss-backward (mln-last-layer net))))
    (dolist (layer (reverse (mln-layers net)))
      (setq dout (backward layer dout))))
  (let ((grads (make-hash-table :test 'equal))
        (lam (mln-weight-decay-lambda net))
        (idx 0))
    (dolist (aff (mln-affines net))
      (setq idx (+ idx 1))
      (setf (gethash (format nil "W~a" idx) grads)
            (linalg:add (affine-dw aff) (linalg:mul lam (affine-w aff))))
      (setf (gethash (format nil "b~a" idx) grads) (affine-db aff)))
    grads))

(defmethod net-numerical-gradient ((net multi-layer-net) x target)
  (let ((grads (make-hash-table :test 'equal))
        (loss-w (lambda (w) (net-loss net x target))))
    (dolist (key (mln-keys net))
      (setf (gethash key grads)
            (numerical-gradient loss-w (gethash key (mln-params net)))))
    grads))
