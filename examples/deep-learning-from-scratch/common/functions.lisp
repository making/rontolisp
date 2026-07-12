;; common/functions.py -- activation and loss functions (Deep Learning from
;; Scratch). Everything is batch-oriented over linalg arrays: x is a vector
;; or an (N x D) matrix, and the axis reductions / broadcasting follow the
;; book's numpy code line for line.

(defun identity-function (x)
  x)

(defun step-function (x)
  ;; np.array(x > 0, dtype=int) -- a 0.0/1.0 mask here.
  (linalg:greater x 0))

(defun sigmoid (x)
  ;; 1 / (1 + exp(-x))
  (linalg:div 1 (linalg:add 1 (linalg:exp (linalg:negative x)))))

(defun sigmoid-grad (x)
  ;; (1 - sigmoid(x)) * sigmoid(x)
  (let ((s (sigmoid x)))
    (linalg:mul (linalg:sub 1 s) s)))

(defun relu (x)
  ;; np.maximum(0, x)
  (linalg:relu x))

(defun relu-grad (x)
  ;; grad[x >= 0] = 1 -- the boolean mask IS the gradient.
  (linalg:greater-equal x 0))

(defun softmax (x)
  ;; exp(x - rowmax) / rowsum, stabilized along the last axis exactly like
  ;; the book: x - np.max(x, axis=-1, keepdims=True), then the keepdims sum
  ;; broadcasts back over each row. Works for a vector and an (N x D) batch.
  (let* ((shifted (linalg:sub x (linalg:amax x -1 t)))
         (e (linalg:exp shifted)))
    (linalg:div e (linalg:sum e -1 t))))

(defun sum-squared-error (y target)
  ;; 0.5 * sum((y - t)^2)
  (* 0.5 (linalg:sum (linalg:square (linalg:sub y target)))))

(defun cross-entropy-error (y target)
  ;; The book's four-case loss: y and target may be a single sample (vector)
  ;; or a batch (matrix), and target may be one-hot or a plain label vector.
  ;; One-hot targets are collapsed to labels with argmax(axis=1), then the
  ;; per-row correct-class probabilities are picked out with gather --
  ;; y[np.arange(batch_size), t] in the book.
  (let* ((yb (if (= (linalg:ndim y) 1)
                 (linalg:reshape y (list 1 (linalg:size y)))
                 y))
         (tb (if (= (linalg:ndim target) 1)
                 (if (= (linalg:size target) (linalg:size yb))
                     (linalg:reshape target (list 1 (linalg:size target)))
                     target)
                 target))
         (tl (if (= (linalg:size tb) (linalg:size yb))
                 (linalg:argmax tb 1)
                 (linalg:flatten tb)))
         (batch (car (linalg:shape yb))))
    (- 0 (/ (linalg:sum (linalg:log (linalg:add (linalg:gather yb tl) 1.0e-7)))
            batch))))

(defun softmax-loss (x target)
  (cross-entropy-error (softmax x) target))
