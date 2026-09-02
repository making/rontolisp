;;;; `linalg:where`'s MASK WIDTH on Metal (todo-645), per call at the book's score shape.
;;;;
;;;; Until todo-645 `MetalGemm.whereF` declined a `double[]` mask outright, so the select
;;;; ran on the CPU over a MATERIALIZED score -- and `linalg:ones` builds double, which is
;;;; what `torch:subsequent-mask` and `torch:padding-mask` hand `torch:masked-fill`.
;;;; todo-643 folded the mask into the softmax pair, but only for a mask that is a
;;;; TRAILING BLOCK of the score; a padding mask (`(batch 1 length)` over a
;;;; `(batch query key)` score) is not, so that composition still falls back to the
;;;; defun's three members and the fill among them is this `linalg:where`.
;;;;
;;;; Since todo-495 the command buffers are asynchronous and results are lazy, so wall per
;;;; call is NO LONGER device time: an accepted member is an enqueue. Both tables are
;;;; printed for that reason -- the first FORCES each result home (one `aref`, which is
;;;; the whole call plus a 16.8 MB download every rep) and the second does not (the
;;;; enqueue alone). A member that runs on the CPU costs the same in both.
;;;;
;;;;   JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
;;;;   java -jar $JAR .todo/123-gpu-acceleration/mtl-where-mask-width.lisp -o Mw.class \
;;;;        --class-name Mw --gpu --simd
;;;;   java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED Mw
;;;;
;;;; Not project code: a probe, like everything else in this directory.

(defparameter *neg-infinity* (/ -1.0 0.0))

(defparameter *reps* 30)

(defparameter *rounds* 3)

(defparameter *scale* (sqrt (* 1.0 64)))

;; The book's score: (64 256 256) per head, f32.

(defparameter *x*
  (linalg:reshape
   (linalg:linspace -3.0 3.0 4194304 :element-type 'single-float) '(64 256 256)))

;; The causal mask, (1 256 256): a trailing block of the score, so todo-643's fold takes
;; it. DOUBLE, which is what linalg:ones makes.

(defparameter *causal*
  (linalg:expand-dims (linalg:triu (linalg:ones '(256 256)) :k 1) 0))

(defparameter *causal-f*
  (linalg:expand-dims
   (linalg:triu (linalg:ones '(256 256) :element-type 'single-float) :k 1) 0))

;; A PADDING mask, (64 1 256): broadcast over the query axis, so NOT a trailing block of
;; the score -- todo-643's fold refuses the shape and the defun's three members run.

(defparameter *padding*
  (linalg:reshape (linalg:triu (linalg:ones '(64 256)) :k 3) '(64 1 256)))

(defparameter *padding-f*
  (linalg:reshape
   (linalg:triu (linalg:ones '(64 256) :element-type 'single-float) :k 3) '(64 1 256)))

(defparameter *labels* nil)

(defparameter *best* (make-hash-table :test 'equal))

(defun force (a)
  ;; One element read brings a lazy result home; the whole array comes with it.
  (aref a 0 0 0))

(defun run (label force-p thunk)
  ;; One round of *reps* calls; the best round wins. get-internal-real-time is
  ;; milliseconds here, so the division gives ms per call.
  (let ((key (list label force-p)))
    (unless (gethash key *best*)
      (push key *labels*)
      (setf (gethash key *best*) 1e30))
    (let ((t0 (get-internal-real-time)))
      (dotimes (i *reps*)
        (let ((r (funcall thunk))) (when force-p (force r))))
      (let ((ms (/ (float (- (get-internal-real-time) t0)) *reps*)))
        (when (< ms (gethash key *best*)) (setf (gethash key *best*) ms))))))

(defparameter *cases*
  (list
   (cons "where, f64 mask (1 256 256)"
         (lambda () (linalg:where *causal* *neg-infinity* *x*)))
   (cons "where, f32 mask (1 256 256)"
         (lambda () (linalg:where *causal-f* *neg-infinity* *x*)))
   (cons "where, f64 mask, the adjoint's zero fill"
         (lambda () (linalg:where *causal* 0.0 *x*)))
   (cons "where, f64 mask (64 1 256), a padding mask"
         (lambda () (linalg:where *padding* *neg-infinity* *x*)))
   (cons "where, f32 mask (64 1 256), a padding mask"
         (lambda () (linalg:where *padding-f* *neg-infinity* *x*)))
   ;; The composition todo-643 folds, at a mask shape the fold REFUSES: the defun runs
   ;; div, where and softmax as three members, and the where is the one at issue.
   (cons "scaled-masked-softmax, f64 padding mask (fold refuses the shape)"
         (lambda ()
           (linalg::%la-scaled-masked-softmax *x* *scale* *padding* *neg-infinity* -1)))
   (cons "scaled-masked-softmax, f32 padding mask (fold refuses the shape)"
         (lambda ()
           (linalg::%la-scaled-masked-softmax *x* *scale* *padding-f* *neg-infinity* -1)))
   ;; And the fold's own shape, which must not move: it never went through whereF.
   (cons "scaled-masked-softmax, f64 causal mask (the fold takes it)"
         (lambda ()
           (linalg::%la-scaled-masked-softmax *x* *scale* *causal* *neg-infinity* -1)))))

(dotimes (warm 3)
  (dolist (c *cases*) (force (funcall (cdr c)))))

(dotimes (round *rounds*)
  (dolist (c *cases*) (run (car c) t (cdr c)))
  (dolist (c *cases*) (run (car c) nil (cdr c))))

(format t "~%~a reps, best of ~a rounds, ms per call~%" *reps* *rounds*)
(format t "~%  forced home (the call plus a 16.8 MB download)~%")
(dolist (key (reverse *labels*))
  (when (cadr key) (format t "    ~,3f  ~a~%" (gethash key *best*) (car key))))
(format t "~%  enqueue only (no result read)~%")
(dolist (key (reverse *labels*))
  (unless (cadr key) (format t "    ~,3f  ~a~%" (gethash key *best*) (car key))))

;; The claim the change rests on: the select is the same bits whichever width carried the
;; mask, and the same bits the CPU produces.
(dolist (pair (list (cons *causal* *causal-f*) (cons *padding* *padding-f*)))
  (let ((a (linalg:where (car pair) *neg-infinity* *x*))
        (b (linalg:where (cdr pair) *neg-infinity* *x*)))
    ;; A subtraction would make NaN out of the two -inf cells, so equality it is.
    (format t "~%where(f64 mask) == where(f32 mask) everywhere: ~a~%"
            (= 1.0 (linalg:amin (linalg:equal a b))))))
