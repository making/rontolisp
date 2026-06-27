;;;; Generalized multi-layer perceptron in rontolisp
;;;; A network is a list of layers; each layer is (W b):
;;;;   W = out x in weight matrix (rank-2 array), b = out-length bias vector.
;;;; Task: binary classification of 2-D points (inside vs. outside a circle),
;;;; a non-linearly-separable problem. We train with SGD + backprop and
;;;; report accuracy on a held-out test set.
;;;;
;;;; Vectors are rank-1 arrays and matrices are rank-2 arrays, so the forward
;;;; and backward passes are plain indexed loops with O(1) aref access and
;;;; in-place weight updates. A layer's shape is read off its bias and input
;;;; vectors via (length v), so no dimensions are threaded through the code.

;;; ---------------------------------------------------------------------------
;;; Randomness via the built-in random.  random returns a value in [0, limit)
;;; of the limit's type; on the interpreter/JVM it draws from Math.random(),
;;; on WASM from the WASI random_get host function (so every run differs).
;;; ---------------------------------------------------------------------------

(defun random-weight () (- (random 1.0) 0.5))   ; -> (-0.5, 0.5)

;;; ---------------------------------------------------------------------------
;;; Array construction
;;; ---------------------------------------------------------------------------

(defun random-vector (n)
  (let ((v (make-array n :initial-element 0.0)))
    (dotimes (i n) (setf (aref v i) (random-weight)))
    v))

(defun random-matrix (rows cols)
  (let ((m (make-array (list rows cols) :initial-element 0.0)))
    (dotimes (i rows)
      (dotimes (j cols) (setf (aref m i j) (random-weight))))
    m))

(defun list->vector (lst)                ; pack a list into a rank-1 array
  (let ((v (make-array (length lst) :initial-element 0.0))
        (i 0))
    (dolist (e lst) (setf (aref v i) e) (setq i (+ i 1)))
    v))

;;; ---------------------------------------------------------------------------
;;; Activation and one layer:  a = sigmoid(W x + b)
;;; ---------------------------------------------------------------------------

(defun sigmoid (x) (/ 1.0 (+ 1.0 (exp (- 0.0 x)))))

(defun layer-forward (w b x)
  (let* ((rows (length b))
         (cols (length x))
         (a (make-array rows :initial-element 0.0)))
    (dotimes (i rows)
      (let ((s (aref b i)))
        (dotimes (j cols) (incf s (* (aref w i j) (aref x j))))
        (setf (aref a i) (sigmoid s))))
    a))

;;; ---------------------------------------------------------------------------
;;; Network construction
;;; ---------------------------------------------------------------------------

(defun layer-w (layer) (first layer))
(defun layer-b (layer) (second layer))

;; sizes = (n0 n1 ... nL): build one layer per consecutive pair.
(defun init-layers (sizes)
  (if (null (rest sizes))
      nil
      (cons (list (random-matrix (second sizes) (first sizes))
                  (random-vector (second sizes)))
            (init-layers (rest sizes)))))

;;; ---------------------------------------------------------------------------
;;; Forward pass.  Collect every activation, input first: (a0=x a1 ... aL)
;;; ---------------------------------------------------------------------------

(defun forward-all (layers x)
  (let ((acts (list x)) (a x))
    (dolist (layer layers)
      (setq a (layer-forward (layer-w layer) (layer-b layer) a))
      (setq acts (cons a acts)))
    (reverse acts)))

(defun predict (layers x)                ; output activation vector
  (car (last (forward-all layers x))))

;;; ---------------------------------------------------------------------------
;;; Backpropagation over a single example (updates the layers in place).
;;; Walk layers from the output backward, carrying the running delta vector.
;;; The new delta must be computed from W before W is updated.
;;; ---------------------------------------------------------------------------

(defun output-delta (aL y)               ; (aL - y) * aL * (1 - aL)
  (let* ((n (length aL))
         (delta (make-array n :initial-element 0.0)))
    (dotimes (i n)
      (setf (aref delta i)
            (* (- (aref aL i) (aref y i)) (aref aL i) (- 1.0 (aref aL i)))))
    delta))

(defun train-example (layers x y lr)
  (let* ((acts (forward-all layers x))            ; (a0 ... aL)
         (rev-layers (reverse layers))            ; (layerL ... layer1)
         (rev-acts (reverse acts))                ; (aL ... a0)
         (delta (output-delta (first rev-acts) y))
         (cur (rest rev-acts)))                   ; (a_{prev} ... a0)
    (dolist (layer rev-layers)
      (let* ((w (layer-w layer)) (b (layer-b layer))
             (a-prev (first cur))
             (rows (length b)) (cols (length a-prev))
             (new-delta (make-array cols :initial-element 0.0)))
        ;; new-delta = (W^T delta) * a-prev * (1 - a-prev), read W before updating
        (dotimes (j cols)
          (let ((s 0.0))
            (dotimes (i rows) (incf s (* (aref w i j) (aref delta i))))
            (setf (aref new-delta j)
                  (* s (aref a-prev j) (- 1.0 (aref a-prev j))))))
        ;; descend: W -= lr * delta (outer) a-prev, b -= lr * delta
        (dotimes (i rows)
          (dotimes (j cols)
            (decf (aref w i j) (* lr (aref delta i) (aref a-prev j))))
          (decf (aref b i) (* lr (aref delta i))))
        (setq delta new-delta)
        (setq cur (rest cur))))
    layers))

;;; ---------------------------------------------------------------------------
;;; Loss, accuracy, training loop
;;; ---------------------------------------------------------------------------

(defun example-loss (layers ex)
  (let* ((a (predict layers (first ex)))
         (y (second ex))
         (s 0.0))
    (dotimes (i (length a))
      (let ((d (- (aref a i) (aref y i)))) (incf s (* d d))))
    (* 0.5 s)))

(defun total-loss (layers data)
  (let ((s 0.0))
    (dolist (ex data) (incf s (example-loss layers ex)))
    s))

(defun classify (layers x)               ; threshold the single output
  (if (> (aref (predict layers x) 0) 0.5) 1.0 0.0))

(defun accuracy (layers data)
  (let ((correct 0))
    (dolist (ex data)
      (when (= (classify layers (first ex)) (aref (second ex) 0))
        (setq correct (+ correct 1))))
    (/ (float correct) (length data))))

(defun train (layers data epochs lr)
  (let ((e 0))
    (while (< e epochs)
      (dolist (ex data)
        (train-example layers (first ex) (second ex) lr))
      (when (zerop (mod e 200))
        (format t "epoch ~a  loss ~a  train-acc ~a~%"
                e (total-loss layers data) (accuracy layers data)))
      (setq e (+ e 1))))
  layers)

;;; ---------------------------------------------------------------------------
;;; Synthetic data: point is class 1 if inside circle radius^2 < 0.5,
;;; coordinates drawn uniformly from [-1, 1)^2 via (- (random 2.0) 1.0).
;;; Each example is (input-vector target-vector), both rank-1 arrays.
;;; ---------------------------------------------------------------------------

(defun make-point ()
  (let* ((x (- (random 2.0) 1.0))
         (y (- (random 2.0) 1.0))
         (label (if (< (+ (* x x) (* y y)) 0.5) 1.0 0.0)))
    (list (list->vector (list x y)) (list->vector (list label)))))

(defun make-dataset (n)
  (let ((acc nil) (i 0))
    (while (< i n)
      (setq acc (cons (make-point) acc))
      (setq i (+ i 1)))
    acc))

;;; ---------------------------------------------------------------------------
;;; Run
;;; ---------------------------------------------------------------------------

(defparameter *train* (make-dataset 150))
(defparameter *test*  (make-dataset 60))

(format t "Circle classification, 2-8-1 MLP~%")
(format t "train=~a examples, test=~a examples~%~%"
        (length *train*) (length *test*))

(defparameter *net* (init-layers (list 2 8 1)))
(setq *net* (train *net* *train* 2000 0.5))

(format t "~%Final train accuracy: ~a~%" (accuracy *net* *train*))
(format t "Final test  accuracy: ~a~%" (accuracy *net* *test*))
