;;;; Feed-forward neural network in rontolisp
;;;; Learns the XOR function via backpropagation + gradient descent.
;;;; Topology: 2 inputs -> 4 hidden (sigmoid) -> 1 output (sigmoid).

;;; --- random weights via the built-in random ---
;;; random returns a value in [0, limit) of the limit's type. On the interpreter
;;; and JVM backends it draws from Math.random(); on WASM it draws real entropy
;;; from the WASI random_get host function (so every run differs).
(defun random-weight () (- (random 1.0) 0.5))   ; -> (-0.5, 0.5)

;;; --- vector / matrix helpers ---
(defun build-list (n fn)
  (let ((acc nil) (i 0))
    (while (< i n)
      (setq acc (cons (funcall fn) acc))
      (setq i (+ i 1)))
    (reverse acc)))
(defun map2 (fn a b)
  (if (null a) nil
      (cons (funcall fn (car a) (car b)) (map2 fn (cdr a) (cdr b)))))
(defun dot (a b) (reduce #'+ (map2 #'* a b) :initial-value 0))
(defun vec+ (a b) (map2 #'+ a b))
(defun vec- (a b) (map2 #'- a b))
(defun vec-scale (s v) (mapcar (lambda (x) (* s x)) v))
(defun hadamard (a b) (map2 #'* a b))
(defun mat-vec (m v) (mapcar (lambda (row) (dot row v)) m))
(defun transpose (m)
  (if (null (car m)) nil
      (cons (mapcar #'car m) (transpose (mapcar #'cdr m)))))
(defun mat-vec-T (m v) (mat-vec (transpose m) v))
(defun outer (a b) (mapcar (lambda (ai) (vec-scale ai b)) a))
(defun mat- (m1 m2) (map2 #'vec- m1 m2))
(defun mat-scale (s m) (mapcar (lambda (row) (vec-scale s row)) m))

;;; --- activation ---
(defun sigmoid (x) (/ 1.0 (+ 1.0 (exp (- 0 x)))))
(defun vec-sigmoid (v) (mapcar #'sigmoid v))
(defun dsigmoid-from-a (v) (mapcar (lambda (a) (* a (- 1.0 a))) v))

;;; --- network = (W1 b1 W2 b2) ---
(defun random-vector (n) (build-list n #'random-weight))
(defun random-matrix (rows cols) (build-list rows (lambda () (random-vector cols))))
(defun init-net (n-in n-hid n-out)
  (list (random-matrix n-hid n-in) (random-vector n-hid)
        (random-matrix n-out n-hid) (random-vector n-out)))
(defun net-w1 (net) (first net))
(defun net-b1 (net) (second net))
(defun net-w2 (net) (third net))
(defun net-b2 (net) (fourth net))

(defun forward-output (net x)
  (let* ((a1 (vec-sigmoid (vec+ (mat-vec (net-w1 net) x) (net-b1 net)))))
    (vec-sigmoid (vec+ (mat-vec (net-w2 net) a1) (net-b2 net)))))

;;; --- one backprop / SGD step over a single example ---
(defun train-example (net x y lr)
  (let* ((w1 (net-w1 net)) (b1 (net-b1 net)) (w2 (net-w2 net)) (b2 (net-b2 net))
         (a1 (vec-sigmoid (vec+ (mat-vec w1 x) b1)))
         (a2 (vec-sigmoid (vec+ (mat-vec w2 a1) b2)))
         (d2 (hadamard (vec- a2 y) (dsigmoid-from-a a2)))
         (d1 (hadamard (mat-vec-T w2 d2) (dsigmoid-from-a a1)))
         (w2n (mat- w2 (mat-scale lr (outer d2 a1))))
         (b2n (vec- b2 (vec-scale lr d2)))
         (w1n (mat- w1 (mat-scale lr (outer d1 x))))
         (b1n (vec- b1 (vec-scale lr d1))))
    (list w1n b1n w2n b2n)))

;;; --- loss + training loop ---
(defun example-loss (net ex)
  (let* ((yhat (forward-output net (first ex)))
         (diff (vec- yhat (second ex))))
    (* 0.5 (dot diff diff))))
(defun total-loss (net data)
  (reduce #'+ (mapcar (lambda (ex) (example-loss net ex)) data) :initial-value 0))
(defun train (net data epochs lr)
  (let ((e 0))
    (while (< e epochs)
      (dolist (ex data)
        (setq net (train-example net (first ex) (second ex) lr)))
      (when (zerop (mod e 1000))
        (format t "epoch ~a  loss ~a~%" e (total-loss net data)))
      (setq e (+ e 1))))
  net)

;;; --- run: learn XOR ---
(defparameter *xor-data*
  (list (list (list 0.0 0.0) (list 0.0))
        (list (list 0.0 1.0) (list 1.0))
        (list (list 1.0 0.0) (list 1.0))
        (list (list 1.0 1.0) (list 0.0))))

(defparameter *net* (init-net 2 4 1))
(format t "Training XOR (2-4-1 network)...~%")
(setq *net* (train *net* *xor-data* 10000 0.5))

(format t "~%Predictions after training:~%")
(dolist (ex *xor-data*)
  (let ((x (first ex)))
    (format t "  ~a -> ~a  (target ~a)~%"
            x (first (forward-output *net* x)) (first (second ex)))))
