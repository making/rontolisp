;;;; Feed-forward neural network in rontolisp
;;;; Learns the XOR function via backpropagation + gradient descent.
;;;; Topology: 2 inputs -> 4 hidden (sigmoid) -> 1 output (sigmoid).
;;;;
;;;; Vectors are rank-1 arrays and weight matrices are rank-2 arrays, so the
;;;; math is plain indexed arithmetic with O(1) aref access and in-place weight
;;;; updates -- close to how a real network is written. (length v) gives a
;;;; vector's size, so layer shapes are read off the bias/input vectors rather
;;;; than tracked separately.

;;; --- random weights via the built-in random ---
;;; random returns a value in [0, limit) of the limit's type. On the interpreter
;;; and JVM backends it draws from Math.random(); on WASM it draws real entropy
;;; from the WASI random_get host function (so every run differs).
(defun random-weight () (- (random 1.0) 0.5)) ; -> (-0.5, 0.5)

;;; --- array helpers ---
(defun random-vector (n)
  (let ((v (make-array n :initial-element 0.0)))
    (dotimes (i n) (setf (aref v i) (random-weight)))
    v))
(defun random-matrix (rows cols)
  (let ((m (make-array (list rows cols) :initial-element 0.0)))
    (dotimes (i rows) (dotimes (j cols) (setf (aref m i j) (random-weight))))
    m))

;;; --- activation ---
(defun sigmoid (x) (/ 1.0 (+ 1.0 (exp (- 0.0 x)))))

;;; One layer: a = sigmoid(W x + b). The output length is (length b) and the
;;; input length is (length x), so no dimensions need to be passed around.
(defun layer-forward (w b x)
  (let* ((rows (length b))
         (cols (length x))
         (a (make-array rows :initial-element 0.0)))
    (dotimes (i rows)
      (let ((s (aref b i)))
        (dotimes (j cols) (incf s (* (aref w i j) (aref x j))))
        (setf (aref a i) (sigmoid s))))
    a))

;;; --- network = (W1 b1 W2 b2) ---
(defun init-net (n-in n-hid n-out)
  (list (random-matrix n-hid n-in) (random-vector n-hid)
        (random-matrix n-out n-hid) (random-vector n-out)))
(defun net-w1 (net) (first net))
(defun net-b1 (net) (second net))
(defun net-w2 (net) (third net))
(defun net-b2 (net) (fourth net))

(defun forward-output (net x)
  (layer-forward (net-w2 net) (net-b2 net)
                 (layer-forward (net-w1 net) (net-b1 net) x)))

;;; --- one backprop / SGD step over a single example (updates net in place) ---
(defun train-example (net x y lr)
  (let* ((w1 (net-w1 net))
         (b1 (net-b1 net))
         (w2 (net-w2 net))
         (b2 (net-b2 net))
         (n-in (length x))
         (n-hid (length b1))
         (n-out (length b2))
         (a1 (layer-forward w1 b1 x))
         (a2 (layer-forward w2 b2 a1))
         (d2 (make-array n-out :initial-element 0.0))
         (d1 (make-array n-hid :initial-element 0.0)))
    ;; output delta: (a2 - y) * a2 * (1 - a2)
    (dotimes (i n-out)
      (setf (aref d2 i)
            (* (- (aref a2 i) (aref y i)) (aref a2 i) (- 1.0 (aref a2 i)))))
    ;; hidden delta: (W2^T d2) * a1 * (1 - a1)  -- computed before W2 changes
    (dotimes (j n-hid)
      (let ((s 0.0))
        (dotimes (i n-out) (incf s (* (aref w2 i j) (aref d2 i))))
        (setf (aref d1 j) (* s (aref a1 j) (- 1.0 (aref a1 j))))))
    ;; descend: W -= lr * delta (outer) input, b -= lr * delta
    (dotimes (i n-out)
      (dotimes (j n-hid) (decf (aref w2 i j) (* lr (aref d2 i) (aref a1 j))))
      (decf (aref b2 i) (* lr (aref d2 i))))
    (dotimes (j n-hid)
      (dotimes (k n-in) (decf (aref w1 j k) (* lr (aref d1 j) (aref x k))))
      (decf (aref b1 j) (* lr (aref d1 j))))
    net))

;;; --- loss + training loop ---
(defun example-loss (net ex)
  (let* ((a (forward-output net (first ex))) (y (second ex)) (s 0.0))
    (dotimes (i (length a))
      (let ((d (- (aref a i) (aref y i)))) (incf s (* d d))))
    (* 0.5 s)))
(defun total-loss (net data)
  (let ((s 0.0))
    (dolist (ex data) (incf s (example-loss net ex)))
    s))
(defun train (net data epochs lr)
  (let ((e 0))
    (while (< e epochs)
      (dolist (ex data) (train-example net (first ex) (second ex) lr))
      (when (zerop (mod e 1000))
        (format t "epoch ~a  loss ~a~%" e (total-loss net data)))
      (setq e (+ e 1))))
  net)

;;; --- run: learn XOR ---
;;; Inputs and targets are vector literals (#(...)) read directly as rank-1
;;; arrays. They are never mutated -- only the network's weights change.
(defparameter *xor-data*
  (list (list #(0.0 0.0) #(0.0)) (list #(0.0 1.0) #(1.0))
        (list #(1.0 0.0) #(1.0)) (list #(1.0 1.0) #(0.0))))

(defparameter *net* (init-net 2 4 1))
(format t "Training XOR (2-4-1 network)...~%")
(setq *net* (train *net* *xor-data* 10000 0.5))

(format t "~%Predictions after training:~%")
(dolist (ex *xor-data*)
  (let ((x (first ex)))
    (format t "  ~a ~a -> ~a  (target ~a)~%" (aref x 0) (aref x 1)
            (aref (forward-output *net* x) 0) (aref (second ex) 0))))
