;;;; common.lisp -- math shared by the trainer and the inference program.
;;;;
;;;; This file is concatenated FIRST into both train.lisp (offline, runs on the
;;;; interpreter/JVM) and infer.lisp (compiled to WASM and run in the browser),
;;;; so it must only use forms supported by all backends: make-array / aref /
;;;; setf / dotimes / dolist / exp.  The image is a 16x16 grid flattened
;;;; row-major into a length-256 vector of values in [0, 1].

;;; A network is a list of layers; each layer is (W b) where W is an
;;; out x in rank-2 array and b is an out-length rank-1 vector.  The forward
;;; pass is a = sigmoid(W x + b), exactly as in examples/mlp.lisp, generalized
;;; to an arbitrary output width (here: one unit per hiragana class).

(defun sigmoid (x) (/ 1.0 (+ 1.0 (exp (- 0.0 x)))))

(defun list->vector (lst)                ; pack a list into a rank-1 array
  (let ((v (make-array (length lst) :initial-element 0.0))
        (i 0))
    (dolist (e lst) (setf (aref v i) e) (setq i (+ i 1)))
    v))

;; Rebuild an out x in matrix from a flat row-major list (used to revive the
;; baked-in / freshly-trained weights into indexable arrays).
(defun mat-from-flat (rows cols flat)
  (let ((m (make-array (list rows cols) :initial-element 0.0))
        (k 0))
    (dotimes (i rows)
      (dotimes (j cols)
        (setf (aref m i j) (nth k flat))
        (setq k (+ k 1))))
    m))

(defun layer-w (layer) (first layer))
(defun layer-b (layer) (second layer))

(defun layer-forward (w b x)             ; a = sigmoid(W x + b)
  (let* ((rows (length b))
         (cols (length x))
         (a (make-array rows :initial-element 0.0)))
    (dotimes (i rows)
      (let ((s (aref b i)))
        (dotimes (j cols) (incf s (* (aref w i j) (aref x j))))
        (setf (aref a i) (sigmoid s))))
    a))

;; Collect every activation, input first: (a0=x a1 ... aL).
(defun forward-all (layers x)
  (let ((acts (list x)) (a x))
    (dolist (layer layers)
      (setq a (layer-forward (layer-w layer) (layer-b layer) a))
      (setq acts (cons a acts)))
    (reverse acts)))

(defun predict (layers x)                ; output activation vector
  (car (last (forward-all layers x))))

(defun argmax (v)                        ; index of the largest component
  (let ((best 0) (bv (aref v 0)))
    (dotimes (i (length v))
      (when (> (aref v i) bv)
        (setq bv (aref v i))
        (setq best i)))
    best))
