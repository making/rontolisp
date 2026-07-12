;; ch04/two_layer_net.py -- the two-layer network, ch04 style (Deep
;; Learning from Scratch). A library file loaded by train-neuralnet.lisp.
;;
;; The network is its params dict -- a hash table keyed "W1" "b1" "W2" "b2"
;; like the book's -- and the operations are plain functions over it (the
;; class-based layer architecture arrives in ch05). Both gradient methods
;; are here: the numerical one the chapter builds, and the
;; sigmoid_grad-based analytic backprop the book ships alongside it for
;; speed.

(load "../common/functions.lisp")
(load "../common/gradient.lisp")

(defparameter *tln-keys* '("W1" "b1" "W2" "b2"))

(defun make-two-layer-net (input-size hidden-size output-size
                           &optional (weight-init-std 0.01))
  (let ((params (make-hash-table :test 'equal)))
    (setf (gethash "W1" params)
          (linalg:mul weight-init-std (linalg:randn (list input-size hidden-size))))
    (setf (gethash "b1" params) (linalg:zeros hidden-size))
    (setf (gethash "W2" params)
          (linalg:mul weight-init-std (linalg:randn (list hidden-size output-size))))
    (setf (gethash "b2" params) (linalg:zeros output-size))
    params))

(defun tln-predict (params x)
  (let* ((a1 (linalg:add (linalg:matmul x (gethash "W1" params))
                         (gethash "b1" params)))
         (z1 (sigmoid a1))
         (a2 (linalg:add (linalg:matmul z1 (gethash "W2" params))
                         (gethash "b2" params))))
    (softmax a2)))

(defun tln-loss (params x target)
  (cross-entropy-error (tln-predict params x) target))

(defun tln-accuracy-count (params x target)
  ;; The number of correctly classified rows (one-hot target). An integer
  ;; count prints identically on every backend, where the book's float
  ;; accuracy would not; callers show it as count/total.
  (let ((y (linalg:argmax (tln-predict params x) 1))
        (tl (linalg:argmax target 1)))
    (truncate (linalg:sum (linalg:equal y tl)))))

(defun tln-numerical-gradient (params x target)
  ;; grads via central differences over every parameter element -- the
  ;; ch04 method (slow; used by ch05's gradient check).
  (let ((grads (make-hash-table :test 'equal))
        (loss-w (lambda (w) (tln-loss params x target))))
    (dolist (key *tln-keys*)
      (setf (gethash key grads)
            (numerical-gradient loss-w (gethash key params))))
    grads))

(defun tln-gradient (params x target)
  ;; The book's analytic shortcut: dy = (y - t)/batch, then the chain rule
  ;; through sigmoid_grad -- the un-abstracted form of ch05's backprop.
  (let* ((w2 (gethash "W2" params))
         (batch (car (linalg:shape x)))
         (a1 (linalg:add (linalg:matmul x (gethash "W1" params))
                         (gethash "b1" params)))
         (z1 (sigmoid a1))
         (a2 (linalg:add (linalg:matmul z1 w2) (gethash "b2" params)))
         (y (softmax a2))
         (dy (linalg:div (linalg:sub y target) batch))
         (dz1 (linalg:matmul dy (linalg:transpose w2)))
         (da1 (linalg:mul (sigmoid-grad a1) dz1))
         (grads (make-hash-table :test 'equal)))
    (setf (gethash "W2" grads) (linalg:matmul (linalg:transpose z1) dy))
    (setf (gethash "b2" grads) (linalg:sum dy 0))
    (setf (gethash "W1" grads) (linalg:matmul (linalg:transpose x) da1))
    (setf (gethash "b1" grads) (linalg:sum da1 0))
    grads))

(defun tln-update! (params grads lr)
  ;; params[key] -= lr * grads[key], element-wise IN PLACE like the book's
  ;; training loops (ch05's layers alias these same arrays).
  (dolist (key *tln-keys*)
    (let ((p (gethash key params))
          (g (gethash key grads)))
      (dotimes (k (linalg:size p))
        (setf (row-major-aref p k)
              (- (row-major-aref p k) (* lr (row-major-aref g k))))))))
