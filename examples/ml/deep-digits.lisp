;; A small deep neural network, numpy-style, with the linalg package.
;;
;; Learns to classify the ten digits 0-9 from 5x3 pixel bitmaps using a
;; 15 -> 16 -> 16 -> 10 multi-layer perceptron (two hidden leaky-ReLU
;; layers), trained by full-batch gradient descent with matrix
;; backpropagation and a 1/t learning-rate decay: every forward and
;; backward step is a linalg:matmul / transpose / elementwise operation
;; over the whole 10-sample batch at once, exactly like a numpy
;; implementation. Biases use the classic augmentation trick (a constant-1
;; column appended to each layer input).
;;
;; The example is fully deterministic and prints identical output on every
;; backend: weights are initialized from a hand-written linear congruential
;; generator with a fixed seed, leaky ReLU needs no transcendental
;; functions (float +/-/* are IEEE-identical everywhere), and the loss is
;; reported as a scaled integer to sidestep per-backend float printing
;; differences.
;;
;; Demonstrates: linalg:matmul, transpose, sub, mul (scalar broadcast and
;; Hadamard), emap, sum, argmax, from-list, shape.

;; --- deterministic pseudo-random weights ------------------------------------

(defvar *lcg-state* 42)

(defun lcg-next ()
  ;; A small Lehmer-style linear congruential generator (the ZX Spectrum
  ;; constants). Every intermediate value stays below 2^23, so the integer
  ;; arithmetic fits the WASM backend's i31 range and is deterministic on
  ;; every backend.
  (setq *lcg-state* (mod (+ (* *lcg-state* 75) 74) 65537))
  *lcg-state*)

(defun rand-matrix (rows cols)
  ;; A rows x cols matrix of floats uniformly spread in [-0.25, 0.25).
  (let ((m (make-array (list rows cols) :initial-element 0)))
    (do ((i 0 (+ i 1)))
        ((>= i rows) m)
      (do ((j 0 (+ j 1)))
          ((>= j cols))
        (setf (aref m i j) (- (/ (mod (lcg-next) 1000) 2000.0) 0.25))))))

;; --- matrix helpers on top of linalg -----------------------------------------

(defun with-bias (m)
  ;; Appends a constant-1 column: [m | 1].
  (let* ((shape (linalg:shape m))
         (rows (car shape))
         (cols (car (cdr shape)))
         (out (make-array (list rows (+ cols 1)) :initial-element 1)))
    (do ((i 0 (+ i 1)))
        ((>= i rows) out)
      (do ((j 0 (+ j 1)))
          ((>= j cols))
        (setf (aref out i j) (aref m i j))))))

(defun drop-last-col (m)
  ;; The inverse of with-bias: removes the last column.
  (let* ((shape (linalg:shape m))
         (rows (car shape))
         (cols (- (car (cdr shape)) 1))
         (out (make-array (list rows cols) :initial-element 0)))
    (do ((i 0 (+ i 1)))
        ((>= i rows) out)
      (do ((j 0 (+ j 1)))
          ((>= j cols))
        (setf (aref out i j) (aref m i j))))))

(defun mat-row (m i)
  ;; Row i of a matrix as a fresh vector.
  (let* ((cols (car (cdr (linalg:shape m))))
         (v (make-array cols :initial-element 0)))
    (do ((j 0 (+ j 1)))
        ((>= j cols) v)
      (setf (aref v j) (aref m i j)))))

(defun leaky-relu (m)
  ;; max(x, 0.1x): the small negative slope keeps every unit trainable
  ;; (a plain ReLU here dies with an unlucky init and a whole layer stuck
  ;; at zero gradient).
  (linalg:emap (lambda (x) (if (> x 0) x (* 0.1 x))) m))

(defun leaky-relu-mask (m)
  ;; The derivative of leaky-relu: 1 for positive pre-activations, else 0.1.
  (linalg:emap (lambda (x) (if (> x 0) 1 0.1)) m))

(defun sq-sum (m)
  (linalg:sum (linalg:emap (lambda (x) (* x x)) m)))

;; --- the dataset: 5x3 pixel bitmaps of the digits 0-9 ------------------------

(defun digit-bitmaps ()
  ;; One row per digit, 15 pixels each (row-major 5x3).
  (linalg:from-list
   '((1 1 1  1 0 1  1 0 1  1 0 1  1 1 1)    ; 0
     (0 1 0  1 1 0  0 1 0  0 1 0  1 1 1)    ; 1
     (1 1 1  0 0 1  1 1 1  1 0 0  1 1 1)    ; 2
     (1 1 1  0 0 1  1 1 1  0 0 1  1 1 1)    ; 3
     (1 0 1  1 0 1  1 1 1  0 0 1  0 0 1)    ; 4
     (1 1 1  1 0 0  1 1 1  0 0 1  1 1 1)    ; 5
     (1 1 1  1 0 0  1 1 1  1 0 1  1 1 1)    ; 6
     (1 1 1  0 0 1  0 0 1  0 0 1  0 0 1)    ; 7
     (1 1 1  1 0 1  1 1 1  1 0 1  1 1 1)    ; 8
     (1 1 1  1 0 1  1 1 1  0 0 1  1 1 1)))) ; 9

(defun one-hot-targets ()
  (let ((y (make-array '(10 10) :initial-element 0)))
    (do ((i 0 (+ i 1)))
        ((>= i 10) y)
      (setf (aref y i i) 1))))

;; --- the network --------------------------------------------------------------

(defun forward (a0 w1 w2 w3)
  ;; Returns (z1 a1b z2 a2b out): pre-activations, biased activations, output.
  (let* ((z1 (linalg:matmul a0 w1))
         (a1b (with-bias (leaky-relu z1)))
         (z2 (linalg:matmul a1b w2))
         (a2b (with-bias (leaky-relu z2)))
         (out (linalg:matmul a2b w3)))
    (list z1 a1b z2 a2b out)))

(defun predict (x w1 w2 w3)
  ;; Class = argmax of the output row for each sample.
  (let* ((state (forward (with-bias x) w1 w2 w3))
         (out (nth 4 state))
         (n (car (linalg:shape out)))
         (preds nil))
    (do ((i (- n 1) (- i 1)))
        ((< i 0) preds)
      (setq preds (cons (linalg:argmax (mat-row out i)) preds)))))

(defun main ()
  (let* ((x (digit-bitmaps))
         (y (one-hot-targets))
         (a0 (with-bias x))                 ; 10x16 input batch
         (n (car (linalg:shape x)))
         (lr0 0.25)
         (w1 (rand-matrix 16 16))           ; 15 pixels + bias -> 16
         (w2 (rand-matrix 17 16))           ; 16 hidden + bias -> 16
         (w3 (rand-matrix 17 10)))          ; 16 hidden + bias -> 10 classes
    (format t "network: 15 -> 16 (leaky relu) -> 16 (leaky relu) -> 10, ~a samples~%" n)
    (format t "input batch shape (with bias): ~a~%~%" (linalg:shape a0))
    (do ((epoch 1 (+ epoch 1)))
        ((> epoch 500))
      (let* ((lr (/ lr0 (+ 1 (/ epoch 100.0))))   ; 1/t learning-rate decay
             (state (forward a0 w1 w2 w3))
             (z1 (nth 0 state))
             (a1b (nth 1 state))
             (z2 (nth 2 state))
             (a2b (nth 3 state))
             (out (nth 4 state))
             (diff (linalg:sub out y))
             ;; Backpropagation, all as matrix products:
             ;; dOut = 2/n * (out - y)
             (dout (linalg:mul (/ 2.0 n) diff))
             (dw3 (linalg:matmul (linalg:transpose a2b) dout))
             (dz2 (linalg:mul (drop-last-col (linalg:matmul dout (linalg:transpose w3)))
                              (leaky-relu-mask z2)))
             (dw2 (linalg:matmul (linalg:transpose a1b) dz2))
             (dz1 (linalg:mul (drop-last-col (linalg:matmul dz2 (linalg:transpose w2)))
                              (leaky-relu-mask z1)))
             (dw1 (linalg:matmul (linalg:transpose a0) dz1)))
        (setq w3 (linalg:sub w3 (linalg:mul lr dw3)))
        (setq w2 (linalg:sub w2 (linalg:mul lr dw2)))
        (setq w1 (linalg:sub w1 (linalg:mul lr dw1)))
        (when (= (mod epoch 100) 0)
          ;; MSE loss, scaled to an integer so the output is identical on
          ;; every backend (float printing differs, float arithmetic does not).
          (format t "epoch ~a  loss (x1e6): ~a~%"
                  epoch (round (* 1000000 (/ (sq-sum diff) n)))))))
    (terpri)
    (let ((preds (predict x w1 w2 w3))
          (correct 0))
      (do ((i 0 (+ i 1))
           (rest preds (cdr rest)))
          ((>= i 10))
        (when (= (car rest) i)
          (setq correct (+ correct 1))))
      (format t "predictions on the training digits: ~a~%" preds)
      (format t "accuracy: ~a/10~%~%" correct))
    ;; Generalization: flip one deterministic pixel per bitmap and classify
    ;; again. Two of the corrupted bitmaps are genuinely ambiguous, so the
    ;; two "misses" are reasonable answers: 0 with its centre pixel filled
    ;; in is exactly the bitmap of 8, and 8 with one left-edge pixel cleared
    ;; is one pixel away from both 8 and 2.
    (let ((noisy (linalg:emap (lambda (p) p) (digit-bitmaps))))
      (do ((i 0 (+ i 1)))
          ((>= i 10))
        (let ((j (mod (* 7 (+ i 1)) 15)))
          (setf (aref noisy i j) (- 1 (aref noisy i j)))))
      (format t "predictions with one flipped pixel:  ~a~%"
              (predict noisy w1 w2 w3)))))

(main)
