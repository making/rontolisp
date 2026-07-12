;; common/optimizer.py -- the parameter-update rules (Deep Learning from
;; Scratch): SGD, Momentum, Nesterov, AdaGrad, RMSprop and Adam as CLOS
;; classes sharing one (update optimizer params grads) generic.
;;
;; params and grads are hash tables keyed by strings ("W1", "b1", ...),
;; exactly like the book's dicts, and every rule updates the parameter
;; arrays element-wise IN PLACE -- the layers hold the same array objects
;; (the book's aliasing contract), so an update must never replace them.
;; Optimizer state (v/h/m) is created lazily on the first update, like the
;; book's `if self.v is None`.

(defun %opt-state (params)
  ;; A fresh state dict: one zero array per parameter, matching shapes.
  (let ((state (make-hash-table :test 'equal)))
    (maphash (lambda (key p)
               (setf (gethash key state) (linalg:zeros-like p)))
             params)
    state))

(defgeneric update (optimizer params grads))

;; --- SGD -----------------------------------------------------------------------

(defclass sgd ()
  ((lr :initarg :lr :initform 0.01 :accessor sgd-lr)))

(defmethod update ((opt sgd) params grads)
  (let ((lr (sgd-lr opt)))
    (maphash (lambda (key p)
               (let ((g (gethash key grads)))
                 (dotimes (k (linalg:size p))
                   (setf (row-major-aref p k)
                         (- (row-major-aref p k)
                            (* lr (row-major-aref g k)))))))
             params)))

;; --- Momentum ------------------------------------------------------------------

(defclass momentum ()
  ((lr :initarg :lr :initform 0.01 :accessor mom-lr)
   (momentum :initarg :momentum :initform 0.9 :accessor mom-momentum)
   (v :initform nil :accessor mom-v)))

(defmethod update ((opt momentum) params grads)
  (unless (mom-v opt)
    (setf (mom-v opt) (%opt-state params)))
  (let ((lr (mom-lr opt))
        (m (mom-momentum opt)))
    (maphash (lambda (key p)
               (let ((g (gethash key grads))
                     (v (gethash key (mom-v opt))))
                 (dotimes (k (linalg:size p))
                   (let ((vk (- (* m (row-major-aref v k))
                                (* lr (row-major-aref g k)))))
                     (setf (row-major-aref v k) vk)
                     (setf (row-major-aref p k) (+ (row-major-aref p k) vk))))))
             params)))

;; --- Nesterov ------------------------------------------------------------------

(defclass nesterov ()
  ((lr :initarg :lr :initform 0.01 :accessor nes-lr)
   (momentum :initarg :momentum :initform 0.9 :accessor nes-momentum)
   (v :initform nil :accessor nes-v)))

(defmethod update ((opt nesterov) params grads)
  (unless (nes-v opt)
    (setf (nes-v opt) (%opt-state params)))
  (let ((lr (nes-lr opt))
        (m (nes-momentum opt)))
    (maphash (lambda (key p)
               (let ((g (gethash key grads))
                     (v (gethash key (nes-v opt))))
                 (dotimes (k (linalg:size p))
                   (let ((vk (row-major-aref v k))
                         (gk (row-major-aref g k)))
                     (setf (row-major-aref p k)
                           (- (+ (row-major-aref p k) (* m m vk))
                              (* (+ 1 m) lr gk)))
                     (setf (row-major-aref v k) (- (* m vk) (* lr gk)))))))
             params)))

;; --- AdaGrad -------------------------------------------------------------------

(defclass adagrad ()
  ((lr :initarg :lr :initform 0.01 :accessor ada-lr)
   (h :initform nil :accessor ada-h)))

(defmethod update ((opt adagrad) params grads)
  (unless (ada-h opt)
    (setf (ada-h opt) (%opt-state params)))
  (let ((lr (ada-lr opt)))
    (maphash (lambda (key p)
               (let ((g (gethash key grads))
                     (h (gethash key (ada-h opt))))
                 (dotimes (k (linalg:size p))
                   (let* ((gk (row-major-aref g k))
                          (hk (+ (row-major-aref h k) (* gk gk))))
                     (setf (row-major-aref h k) hk)
                     (setf (row-major-aref p k)
                           (- (row-major-aref p k)
                              (/ (* lr gk) (+ (sqrt hk) 1.0e-7))))))))
             params)))

;; --- RMSprop -------------------------------------------------------------------

(defclass rmsprop ()
  ((lr :initarg :lr :initform 0.01 :accessor rms-lr)
   (decay-rate :initarg :decay-rate :initform 0.99 :accessor rms-decay-rate)
   (h :initform nil :accessor rms-h)))

(defmethod update ((opt rmsprop) params grads)
  (unless (rms-h opt)
    (setf (rms-h opt) (%opt-state params)))
  (let ((lr (rms-lr opt))
        (d (rms-decay-rate opt)))
    (maphash (lambda (key p)
               (let ((g (gethash key grads))
                     (h (gethash key (rms-h opt))))
                 (dotimes (k (linalg:size p))
                   (let* ((gk (row-major-aref g k))
                          (hk (+ (* d (row-major-aref h k))
                                 (* (- 1 d) gk gk))))
                     (setf (row-major-aref h k) hk)
                     (setf (row-major-aref p k)
                           (- (row-major-aref p k)
                              (/ (* lr gk) (+ (sqrt hk) 1.0e-7))))))))
             params)))

;; --- Adam ----------------------------------------------------------------------

(defclass adam ()
  ((lr :initarg :lr :initform 0.001 :accessor adam-lr)
   (beta1 :initarg :beta1 :initform 0.9 :accessor adam-beta1)
   (beta2 :initarg :beta2 :initform 0.999 :accessor adam-beta2)
   (iter :initform 0 :accessor adam-iter)
   (m :initform nil :accessor adam-m)
   (v :initform nil :accessor adam-v)))

(defmethod update ((opt adam) params grads)
  (unless (adam-m opt)
    (setf (adam-m opt) (%opt-state params))
    (setf (adam-v opt) (%opt-state params)))
  (setf (adam-iter opt) (+ (adam-iter opt) 1))
  (let* ((b1 (adam-beta1 opt))
         (b2 (adam-beta2 opt))
         (iter (adam-iter opt))
         ;; the book's bias-corrected step size
         (lr-t (/ (* (adam-lr opt) (sqrt (- 1.0 (expt b2 iter))))
                  (- 1.0 (expt b1 iter)))))
    (maphash (lambda (key p)
               (let ((g (gethash key grads))
                     (m (gethash key (adam-m opt)))
                     (v (gethash key (adam-v opt))))
                 (dotimes (k (linalg:size p))
                   (let* ((gk (row-major-aref g k))
                          (mk (+ (row-major-aref m k)
                                 (* (- 1 b1) (- gk (row-major-aref m k)))))
                          (vk (+ (row-major-aref v k)
                                 (* (- 1 b2) (- (* gk gk) (row-major-aref v k))))))
                     (setf (row-major-aref m k) mk)
                     (setf (row-major-aref v k) vk)
                     (setf (row-major-aref p k)
                           (- (row-major-aref p k)
                              (/ (* lr-t mk) (+ (sqrt vk) 1.0e-7))))))))
             params)))
