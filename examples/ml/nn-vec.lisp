;;;; Feed-forward neural network in rontolisp -- the vec / linalg version of
;;;; nn.lisp. Learns XOR with backprop + gradient descent, 2 -> 4 -> 1.
;;;;
;;;; nn.lisp writes every layer as explicit indexed loops over rank-1/rank-2
;;;; arrays. This version writes NONE of that arithmetic by hand: the whole
;;;; forward and backward pass is expressed with the vec and linalg packages,
;;;; numpy-style, so each line reads as the math it implements.
;;;;
;;;;   - vec: the vector operations. vec:matvec is the forward GEMV (W x, one
;;;;     vectorized dot per row of W -- SIMD-accelerated under the JVM --simd
;;;;     flag); vec:add / vec:sub / vec:mul (Hadamard) / vec:scale build the
;;;;     activations, deltas and bias updates; vec:dot forms the squared loss.
;;;;   - linalg: the matrix operations. linalg:transpose + linalg:dot give the
;;;;     backward W2^T d2; linalg:outer builds each weight gradient (delta (x)
;;;;     input); linalg:mul / linalg:sub apply the update; linalg:emap maps the
;;;;     sigmoid over a whole activation vector; a scalar broadcast gives 1 - a.
;;;;
;;;; The network is a plist (:w1 W1 :b1 b1 :w2 W2 :b2 b2) read with getf, and it
;;;; is immutable: linalg and vec return fresh packed arrays, so train-example
;;;; produces a NEW plist each step rather than mutating in place -- a functional
;;;; SGD loop. Everything is single-float (#f): linalg is width-polymorphic and
;;;; PRESERVES the packed #f width through every op (a constructor opts in with a
;;;; :element-type 'single-float; the transforms follow their input), so a weight update
;;;; (linalg:sub W ...) never widens back to double -- on the JVM --simd path the
;;;; forward and backward pass stay f32 end to end. The same source runs on every
;;;; backend (on wasm-GC the vec: kernels still compute in f64, but the values match).

;;; --- random weights via the built-in random ---
;;; random returns a value in [0, limit) of the limit's type. On the interpreter
;;; and JVM backends it draws from Math.random(); on WASM it draws real entropy
;;; from the WASI random_get host function (so every run differs).
(defun random-weight () (- (random 1.0) 0.5)) ; -> (-0.5, 0.5)

;;; --- array construction (packed single-float #f, shared by vec and linalg) ---
;;; A weight matrix is a rank-2 linalg array; a bias vector is a rank-1 array. Both
;;; opt into single-float: linalg:zeros takes :element-type 'single-float, and a bias
;;; is a bare single-float make-array (the packed type vec: and linalg: both ride on).
(defun random-matrix (rows cols)
  (let ((m (linalg:zeros (list rows cols) :element-type 'single-float)))
    (dotimes (i rows m)
      (dotimes (j cols) (setf (aref m i j) (random-weight))))))
(defun random-vector (n)
  (let ((v (make-array n :element-type 'single-float :initial-element 0.0)))
    (dotimes (i n v) (setf (aref v i) (random-weight)))))

;;; --- activation ---
(defun sigmoid (x) (/ 1.0 (+ 1.0 (exp (- 0.0 x)))))

;;; One layer: a = sigmoid(W x + b). vec:matvec is the GEMV W x, vec:add adds the
;;; bias, and linalg:emap maps sigmoid over the resulting activation vector -- no
;;; indices, no loops.
(defun layer-forward (w b x)
  (linalg:emap #'sigmoid (vec:add (vec:matvec w x) b)))

;;; --- network = the plist (:w1 W1 :b1 b1 :w2 W2 :b2 b2), read with getf ---
(defun init-net (n-in n-hid n-out)
  (list :w1 (random-matrix n-hid n-in)
        :b1 (random-vector n-hid)
        :w2 (random-matrix n-out n-hid)
        :b2 (random-vector n-out)))

(defun forward-output (net x)
  (layer-forward (getf net :w2) (getf net :b2)
                 (layer-forward (getf net :w1) (getf net :b1) x)))

;;; --- one backprop / SGD step over a single example -> a fresh network ---
;;; d2 = (a2 - y) (*) a2 (*) (1 - a2)              [(*) = Hadamard, vec:mul]
;;; d1 = (W2^T d2) (*) a1 (*) (1 - a1)             [W2^T d2 via linalg]
;;; W -= lr * (delta (x) input)                    [outer product, linalg]
;;; b -= lr * delta                                [vec]
(defun train-example (net x y lr)
  (let* ((w1 (getf net :w1))
         (b1 (getf net :b1))
         (w2 (getf net :w2))
         (b2 (getf net :b2))
         (a1 (layer-forward w1 b1 x))
         (a2 (layer-forward w2 b2 a1))
         (d2 (vec:mul (vec:mul (vec:sub a2 y) a2) (linalg:sub 1.0 a2)))
         (d1
          (vec:mul (vec:mul (linalg:dot (linalg:transpose w2) d2) a1)
                   (linalg:sub 1.0 a1))))
    (list :w1 (linalg:sub w1 (linalg:mul (linalg:outer d1 x) lr))
          :b1 (vec:sub b1 (vec:scale d1 lr))
          :w2 (linalg:sub w2 (linalg:mul (linalg:outer d2 a1) lr))
          :b2 (vec:sub b2 (vec:scale d2 lr)))))

;;; --- loss + training loop ---
;;; example loss = 1/2 ||a - y||^2, the squared error via a single vec:dot.
(defun example-loss (net ex)
  (let ((diff (vec:sub (forward-output net (first ex)) (second ex))))
    (* 0.5 (vec:dot diff diff))))
(defun total-loss (net data)
  (let ((s 0.0)) (dolist (ex data s) (incf s (example-loss net ex)))))
(defun train (net data epochs lr)
  (let ((e 0))
    (while (< e epochs)
      (dolist (ex data)
        (setq net (train-example net (first ex) (second ex) lr)))
      (when (zerop (mod e 1000))
        (format t "epoch ~a  loss ~a~%" e (total-loss net data)))
      (setq e (+ e 1)))
    net))

;;; --- run: learn XOR ---
;;; Inputs and targets are packed single-float vector literals (#f(...)) read
;;; directly as rank-1 arrays. They are never mutated -- only the weights change.
(defparameter *xor-data*
  (list (list #f(0.0 0.0) #f(0.0)) (list #f(0.0 1.0) #f(1.0))
        (list #f(1.0 0.0) #f(1.0)) (list #f(1.0 1.0) #f(0.0))))

(defparameter *net* (init-net 2 4 1))
(format t "Training XOR (2-4-1 network, vec + linalg)...~%")
(setq *net* (train *net* *xor-data* 10000 0.5))

(format t "~%Predictions after training:~%")
(dolist (ex *xor-data*)
  (let ((x (first ex)))
    (format t "  ~a ~a -> ~a  (target ~a)~%" (aref x 0) (aref x 1)
            (aref (forward-output *net* x) 0) (aref (second ex) 0))))
