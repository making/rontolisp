;; common/gradient.py -- numerical gradients (Deep Learning from Scratch).
;;
;; The book's numerical_gradient walks every element of x with np.nditer,
;; nudging it +/- h IN PLACE (f re-reads the same array) and restoring it;
;; the row-major-aref walk below is the same thing for any rank. Slow by
;; design -- the book uses it to verify backpropagation, not to train.

(defun numerical-gradient (f x)
  ;; grad[k] = (f(x + h*e_k) - f(x - h*e_k)) / 2h, central differences with
  ;; h = 1e-4. x is temporarily mutated and restored; f takes x itself.
  (let ((h 1.0e-4) (grad (linalg:zeros-like x)))
    (dotimes (k (linalg:size x))
      (let ((tmp (row-major-aref x k)))
        (setf (row-major-aref x k) (+ tmp h))
        (let ((fxh1 (funcall f x)))
          (setf (row-major-aref x k) (- tmp h))
          (let ((fxh2 (funcall f x)))
            (setf (row-major-aref grad k) (/ (- fxh1 fxh2) (* 2 h)))
            (setf (row-major-aref x k) tmp)))))
    grad))
