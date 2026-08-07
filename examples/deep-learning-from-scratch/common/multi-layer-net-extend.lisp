;; common/multi_layer_net_extend.py -- the multi-layer network with
;; Batch Normalization, Dropout and weight decay (Deep Learning from
;; Scratch). A library file for the ch06 scripts.
;;
;; The MultiLayerNet stack extended per hidden layer to
;; Affine -> [BatchNorm] -> activation -> [Dropout]; gamma/beta join the
;; params/grads hashes when BatchNorm is on. The book's train_flg
;; parameter is the special variable *train-p* (layers.lisp): net-gradient
;; binds it to t, net-accuracy-count to nil. Do not load this file and
;; multi-layer-net.lisp into the same program -- each pulls in layers.lisp
;; itself.

(load "layers.lisp")
(load "gradient.lisp")

(defclass multi-layer-net-extend ()
  ((params :initarg :params :accessor mlne-params)
   (layers :initarg :layers :accessor mlne-layers)
   (affines :initarg :affines :accessor mlne-affines)
   (batchnorms :initarg :batchnorms :accessor mlne-batchnorms) ; per hidden, or nil
   (keys :initarg :keys :accessor mlne-keys)
   (weight-decay-lambda :initarg :weight-decay-lambda
                        :accessor mlne-weight-decay-lambda)
   (use-batchnorm :initarg :use-batchnorm :accessor mlne-use-batchnorm)
   (last-layer :initarg :last-layer :accessor mlne-last-layer)))

(defun make-multi-layer-net-extend (input-size hidden-size-list output-size &key
                                               (activation 'relu)
                                               (weight-init-std 'relu)
                                               (weight-decay-lambda 0)
                                               use-dropout (dropout-ratio 0.5)
                                               use-batchnorm)
  (let* ((all-sizes
          (append (list input-size) hidden-size-list (list output-size)))
         (n-hidden (length hidden-size-list))
         (params (make-hash-table :test 'equal))
         (keys nil)
         (affines nil)
         (batchnorms nil)
         (layer-list nil))
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
    (do ((idx 1 (+ idx 1)))
        ((> idx (+ n-hidden 1)))
      (let ((aff
             (make-instance 'affine
                            :w (gethash (format nil "W~a" idx) params)
                            :b (gethash (format nil "b~a" idx) params))))
        (setq affines (cons aff affines))
        (setq layer-list (cons aff layer-list))
        (when (<= idx n-hidden)
          (when use-batchnorm
            (let ((gk (format nil "gamma~a" idx))
                  (bk2 (format nil "beta~a" idx))
                  (size (nth (- idx 1) hidden-size-list)))
              (setf (gethash gk params) (linalg:ones size))
              (setf (gethash bk2 params) (linalg:zeros size))
              (setq keys (append keys (list gk bk2)))
              (let ((bn
                     (make-instance 'batch-normalization
                                    :gamma (gethash gk params)
                                    :beta (gethash bk2 params))))
                (setq batchnorms (cons bn batchnorms))
                (setq layer-list (cons bn layer-list)))))
          (setq layer-list
                (cons (if (eq activation 'sigmoid)
                          (make-instance 'sigmoid-layer)
                          (make-instance 'relu-layer)) layer-list))
          (when use-dropout
            (setq layer-list
                  (cons (make-instance 'dropout :ratio dropout-ratio)
                        layer-list))))))
    (make-instance 'multi-layer-net-extend
                   :params params
                   :layers (reverse layer-list)
                   :affines (reverse affines)
                   :batchnorms (reverse batchnorms)
                   :keys keys
                   :weight-decay-lambda weight-decay-lambda
                   :use-batchnorm use-batchnorm
                   :last-layer (make-instance 'softmax-with-loss))))

;; %init-scale lives in multi-layer-net.lisp in spirit, but the two files
;; are never loaded together, so it is duplicated here verbatim.
(defun %init-scale (weight-init-std prev-size)
  (cond ((numberp weight-init-std) weight-init-std)
        ((member weight-init-std '(relu he)) (sqrt (/ 2.0 prev-size)))
        ((member weight-init-std '(sigmoid xavier)) (sqrt (/ 1.0 prev-size)))
        (t (error "unknown weight-init-std"))))

(defgeneric predict (net x))

(defgeneric net-loss (net x target))

(defgeneric net-gradient (net x target))

(defgeneric net-numerical-gradient (net x target))

(defgeneric net-accuracy-count (net x target))

(defmethod predict ((net multi-layer-net-extend) x)
  (let ((out x))
    (dolist (layer (mlne-layers net)) (setq out (forward layer out)))
    out))

(defmethod net-loss ((net multi-layer-net-extend) x target)
  (let ((decay 0) (lam (mlne-weight-decay-lambda net)))
    (dolist (aff (mlne-affines net))
      (setq decay
            (+ decay (* 0.5 lam (linalg:sum (linalg:square (affine-w aff)))))))
    (+ (loss-forward (mlne-last-layer net) (predict net x) target) decay)))

(defmethod net-accuracy-count ((net multi-layer-net-extend) x target)
  (let* ((y (let ((*train-p* nil)) (predict net x)))
         (yl (linalg:argmax y 1))
         (tl (if (= (linalg:ndim target) 1) target (linalg:argmax target 1))))
    (truncate (linalg:sum (linalg:equal yl tl)))))

(defmethod net-gradient ((net multi-layer-net-extend) x target)
  (let ((*train-p* t))
    (net-loss net x target)
    (let ((dout (loss-backward (mlne-last-layer net))))
      (dolist (layer (reverse (mlne-layers net)))
        (setq dout (backward layer dout)))))
  (let ((grads (make-hash-table :test 'equal))
        (lam (mlne-weight-decay-lambda net))
        (idx 0))
    (dolist (aff (mlne-affines net))
      (setq idx (+ idx 1))
      (setf (gethash (format nil "W~a" idx) grads)
            (linalg:add (affine-dw aff) (linalg:mul lam (affine-w aff))))
      (setf (gethash (format nil "b~a" idx) grads) (affine-db aff)))
    (let ((idx2 0))
      (dolist (bn (mlne-batchnorms net))
        (setq idx2 (+ idx2 1))
        (setf (gethash (format nil "gamma~a" idx2) grads) (bn-dgamma bn))
        (setf (gethash (format nil "beta~a" idx2) grads) (bn-dbeta bn))))
    grads))

(defmethod net-numerical-gradient ((net multi-layer-net-extend) x target)
  (let ((grads (make-hash-table :test 'equal))
        (loss-w (lambda (w) (let ((*train-p* t)) (net-loss net x target)))))
    (dolist (key (mlne-keys net))
      (setf (gethash key grads)
            (numerical-gradient loss-w (gethash key (mlne-params net)))))
    grads))
