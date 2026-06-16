;;;; Generalized multi-layer perceptron in rontolisp
;;;; A network is a list of layers; each layer is (W b):
;;;;   W = out x in weight matrix (list of rows), b = out-length bias vector.
;;;; Task: binary classification of 2-D points (inside vs. outside a circle),
;;;; a non-linearly-separable problem. We train with SGD + backprop and
;;;; report accuracy on a held-out test set.

;;; ---------------------------------------------------------------------------
;;; Randomness via the built-in random.  random returns a value in [0, limit)
;;; of the limit's type; on the interpreter/JVM it draws from Math.random(),
;;; on WASM from a deterministic LCG (so WASM runs are reproducible).
;;; ---------------------------------------------------------------------------

(defun random-weight () (- (random 1.0) 0.5))   ; -> (-0.5, 0.5)

;;; ---------------------------------------------------------------------------
;;; Generic list / vector / matrix helpers
;;; ---------------------------------------------------------------------------

(defun build-list (n fn)                 ; call (fn) n times, collect results
  (let ((acc nil) (i 0))
    (while (< i n)
      (setq acc (cons (funcall fn) acc))
      (setq i (+ i 1)))
    (reverse acc)))

(defun map2 (fn a b)                     ; element-wise map over two lists
  (if (null a)
      nil
      (cons (funcall fn (car a) (car b))
            (map2 fn (cdr a) (cdr b)))))

(defun dot (a b) (reduce #'+ (map2 #'* a b) :initial-value 0))
(defun vec+ (a b) (map2 #'+ a b))
(defun vec- (a b) (map2 #'- a b))
(defun vec-scale (s v) (mapcar (lambda (x) (* s x)) v))
(defun hadamard (a b) (map2 #'* a b))

(defun mat-vec (m v) (mapcar (lambda (row) (dot row v)) m))

(defun transpose (m)
  (if (null (car m))
      nil
      (cons (mapcar #'car m)
            (transpose (mapcar #'cdr m)))))

(defun mat-vec-T (m v) (mat-vec (transpose m) v))
(defun outer (a b) (mapcar (lambda (ai) (vec-scale ai b)) a))
(defun mat- (m1 m2) (map2 #'vec- m1 m2))
(defun mat-scale (s m) (mapcar (lambda (row) (vec-scale s row)) m))

;;; ---------------------------------------------------------------------------
;;; Activation
;;; ---------------------------------------------------------------------------

(defun sigmoid (x) (/ 1.0 (+ 1.0 (exp (- 0 x)))))
(defun vec-sigmoid (v) (mapcar #'sigmoid v))
(defun dsigmoid-from-a (v) (mapcar (lambda (a) (* a (- 1.0 a))) v))

;;; ---------------------------------------------------------------------------
;;; Network construction
;;; ---------------------------------------------------------------------------

(defun random-vector (n) (build-list n #'random-weight))
(defun random-matrix (rows cols)
  (build-list rows (lambda () (random-vector cols))))

;; sizes = (n0 n1 ... nL): build one layer per consecutive pair.
(defun init-layers (sizes)
  (if (null (cdr sizes))
      nil
      (cons (list (random-matrix (second sizes) (first sizes))
                  (random-vector (second sizes)))
            (init-layers (cdr sizes)))))

(defun layer-w (layer) (first layer))
(defun layer-b (layer) (second layer))

;;; ---------------------------------------------------------------------------
;;; Forward pass.  Collect every activation, input first: (a0=x a1 ... aL)
;;; ---------------------------------------------------------------------------

(defun forward-all (layers x)
  (if (null layers)
      (list x)
      (let* ((layer (car layers))
             (a (vec-sigmoid (vec+ (mat-vec (layer-w layer) x) (layer-b layer)))))
        (cons x (forward-all (cdr layers) a)))))

(defun predict (layers x)                ; output activation vector
  (car (last (forward-all layers x))))

;;; ---------------------------------------------------------------------------
;;; Backpropagation over a single example -> updated layers
;;; ---------------------------------------------------------------------------

;; Walk layers from the output backward.  rev-layers = layers reversed
;; (output first); rev-acts-rest = activations strictly below the current
;; layer's output, head = the input fed into the current layer.
(defun backprop-rec (rev-layers rev-acts-rest delta lr)
  (let* ((layer (car rev-layers))
         (w (layer-w layer)) (b (layer-b layer))
         (a-prev (car rev-acts-rest))
         (wn (mat- w (mat-scale lr (outer delta a-prev))))
         (bn (vec- b (vec-scale lr delta)))
         (rest-layers (cdr rev-layers)))
    (if (null rest-layers)
        (list (list wn bn))
        (let ((new-delta (hadamard (mat-vec-T w delta)
                                   (dsigmoid-from-a a-prev))))
          (cons (list wn bn)
                (backprop-rec rest-layers (cdr rev-acts-rest) new-delta lr))))))

(defun train-example (layers x y lr)
  (let* ((acts (forward-all layers x))
         (rev-acts (reverse acts))
         (aL (car rev-acts))
         (delta (hadamard (vec- aL y) (dsigmoid-from-a aL))))
    (reverse (backprop-rec (reverse layers) (cdr rev-acts) delta lr))))

;;; ---------------------------------------------------------------------------
;;; Loss, accuracy, training loop
;;; ---------------------------------------------------------------------------

(defun example-loss (layers ex)
  (let ((diff (vec- (predict layers (first ex)) (second ex))))
    (* 0.5 (dot diff diff))))

(defun total-loss (layers data)
  (reduce #'+ (mapcar (lambda (ex) (example-loss layers ex)) data) :initial-value 0))

(defun classify (layers x)               ; threshold the single output
  (if (> (first (predict layers x)) 0.5) 1.0 0.0))

(defun accuracy (layers data)
  (let ((correct (reduce #'+
                         (mapcar (lambda (ex)
                                   (if (= (classify layers (first ex))
                                          (first (second ex)))
                                       1 0))
                                 data)
                         :initial-value 0)))
    (/ (float correct) (length data))))

(defun train (layers data epochs lr)
  (let ((e 0))
    (while (< e epochs)
      (dolist (ex data)
        (setq layers (train-example layers (first ex) (second ex) lr)))
      (when (zerop (mod e 200))
        (format t "epoch ~a  loss ~a  train-acc ~a~%"
                e (total-loss layers data) (accuracy layers data)))
      (setq e (+ e 1))))
  layers)

;;; ---------------------------------------------------------------------------
;;; Synthetic data: point is class 1 if inside circle radius^2 < 0.5,
;;; coordinates drawn uniformly from [-1, 1)^2 via (- (random 2.0) 1.0).
;;; ---------------------------------------------------------------------------

(defun make-point ()
  (let* ((x (- (random 2.0) 1.0))
         (y (- (random 2.0) 1.0))
         (label (if (< (+ (* x x) (* y y)) 0.5) 1.0 0.0)))
    (list (list x y) (list label))))

(defun make-dataset (n) (build-list n #'make-point))

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
