;; chapter02/section2.lisp -- notebook sections 2.2.2 and 2.2.3, ported.
;;
;; Attention as pure array math: the book's two numpy functions (softmax and
;; attention) over n unit vectors arranged around a circle, the experiment where
;; one key is made five times longer, and the comparison that motivates the
;; 1/sqrt(d_k) scale. No autograd and no modules -- this section is `linalg'
;; alone, which is also why it is the cheapest program of the port.
;;
;; The notebook's four figures become the numbers they were plotted from.
;;
;;   rontolisp chapter02/section2.lisp

(defun np-softmax (x)
  ;; The book's softmax: exp of the max-subtracted input, normalized along the
  ;; last axis. Subtracting the GLOBAL maximum (np.max) rather than the per-row
  ;; one leaves the result unchanged -- softmax is shift invariant per row.
  (let ((e (linalg:exp (linalg:sub x (linalg:amax x)))))
    (linalg:div e (linalg:sum e :axis -1 :keepdims t))))

(defun np-attention (query key value)
  ;; (output attention-weights), the book's two-value return.
  (let ((weights (np-softmax (linalg:dot query (linalg:transpose key)))))
    (list (linalg:dot weights value) weights)))

(defun np-scaled-attention (query key value)
  ;; The same with the 1/sqrt(d) scale of section 2.2.3.
  (let* ((d (car (last (linalg:shape query))))
         (weights
          (linalg:div (linalg:dot query (linalg:transpose key))
                      (sqrt (* 1.0 d))))
         (normalized (np-softmax weights)))
    (list (linalg:dot normalized value) normalized)))

(defun unit-circle-vectors (n)
  ;; n unit vectors spaced evenly around the circle -- the book's `vectors'.
  (let ((v (linalg:zeros (list n 2))) (theta (/ (* 2.0 pi) n)))
    (dotimes (i n)
      (setf (aref v i 0) (cos (* theta i)))
      (setf (aref v i 1) (sin (* theta i))))
    v))

(defun print-weights (label weights)
  (format t "~a:" label)
  (dotimes (i (linalg:size weights)) (format t " ~,3f" (aref weights i)))
  (format t "~%"))

;; --- 2.2.2: attention over ten unit vectors ---------------------------------

(defparameter *n* 10)

(defparameter *vectors* (unit-circle-vectors *n*))

(defparameter *query*
  (linalg:from-list (list (/ 1.0 (sqrt 2.0)) (/ 1.0 (sqrt 2.0)))))

(defparameter *result* (np-attention *query* *vectors* *vectors*))

(format t "output vector: (~,3f ~,3f)~%" (aref (car *result*) 0)
        (aref (car *result*) 1))
(format t "weights shape: ~a~%" (linalg:shape (cadr *result*)))
(format t "weights sum:   ~,3f~%" (linalg:sum (cadr *result*)))
(print-weights "weights" (cadr *result*))

;; The vector nearest the query (45 degrees, between v2 and v3 of the book's
;; 1-based names) must attract the most attention.
(format t "argmax weight: v~a~%" (+ 1 (linalg:argmax (cadr *result*))))

;; --- one key made five times longer -----------------------------------------

(defparameter *long-vectors* (linalg:add *vectors* 0.0))

(dotimes (j 2)
  (setf (aref *long-vectors* 3 j) (* 5.0 (aref *long-vectors* 3 j))))

(defparameter *long-result*
  (np-attention *query* *long-vectors* *long-vectors*))

(print-weights "weights (v4 x5)" (cadr *long-result*))
(format t "argmax weight: v~a~%" (+ 1 (linalg:argmax (cadr *long-result*))))
(format t "largest weight: ~,3f -> ~,3f~%" (linalg:amax (cadr *result*))
        (linalg:amax (cadr *long-result*)))

;; --- three queries at once ---------------------------------------------------

(defparameter *queries*
  (linalg:from-list
   (list (list (/ 1.0 (sqrt 2.0)) (/ 1.0 (sqrt 2.0))) (list 1.0 0.0)
         (list 0.0 1.0))))

(defparameter *batched* (np-attention *queries* *vectors* *vectors*))

(format t "output shape:  ~a~%" (linalg:shape (car *batched*)))
(format t "weights shape: ~a~%" (linalg:shape (cadr *batched*)))
(print-weights "row sums     " (linalg:sum (cadr *batched*) :axis 1))

;; --- 2.2.3: why the scores are scaled ---------------------------------------
;; Twenty random keys against a random query, in 1 and in 100 dimensions, with
;; and without the 1/sqrt(d) scale. Without it the 100-dimensional dot products
;; spread out so far that the softmax collapses onto a single key; the scale
;; brings the largest weight back to the same order as the 1-dimensional case.

(linalg:seed 42)

(defparameter *n-keys* 20)

(dolist (dim '(1 100))
  (let* ((q (linalg:randn (list dim)))
         (k (linalg:randn (list *n-keys* dim)))
         (scores (linalg:dot k q))
         (unscaled (np-softmax scores))
         (scaled (np-softmax (linalg:div scores (sqrt (* 1.0 dim))))))
    (format t "~a dim: score spread ~,2f, largest weight ~,3f -> ~,3f~%" dim
            (- (linalg:amax scores) (linalg:amin scores)) (linalg:amax unscaled)
            (linalg:amax scaled))))

;; The scaled attention of section 2.2.3, over the circle again: the same
;; weights the unscaled call gives, only flatter.
(defparameter *scaled-result* (np-scaled-attention *query* *vectors* *vectors*))

(print-weights "scaled weights" (cadr *scaled-result*))
