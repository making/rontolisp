;;;; Layer-norm's AFFINE on Metal (todo-646), per call at the book's own shapes.
;;;;
;;;; todo-634 folded `torch:layer-norm`'s `* weight + bias` into the normalization as the
;;;; two-output member pair `%la-layer-norm-affine` / `-affine-grad`, and built the kernels
;;;; in `gemm.cu` only: `MetalGemm.layerNormAffineF` / `layerNormAffineGradF` answered
;;;; `false`, so on this backend the pair fell through to the Lisp defun and ran member by
;;;; member. This probe is the measurement that decides whether the fold pays here.
;;;;
;;;; Since todo-495 the command buffers are asynchronous and results are lazy, so wall per
;;;; call is NO LONGER device time for an ACCEPTED member: it is the enqueue. Both tables
;;;; are printed for that reason -- the first FORCES each result home (one `aref`, which is
;;;; the whole call plus a 25.2 MB download every rep) and the second does not. A member
;;;; that runs on the CPU costs the same in both, which is what makes the pair readable.
;;;;
;;;;   JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
;;;;   java -jar $JAR .todo/123-gpu-acceleration/mtl-layer-norm-affine.lisp -o Ln.class \
;;;;        --class-name Ln --gpu --simd
;;;;   java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED Ln
;;;;
;;;; Not project code: a probe, like everything else in this directory.

(defparameter *reps* 40)

(defparameter *rounds* 4)

(defparameter *eps* 1.0e-5)

;; The book's layer-norm activation: batch 64, block 256, n-embd 384 -- (16384 384) f32,
;; 25.2 MB, thirteen of them a step.

(defparameter *rows* 16384)

(defparameter *len* 384)

(defparameter *x*
  (linalg:reshape
   (linalg:linspace -3.0 3.0 (* 16384 384) :element-type 'single-float)
   '(16384 384)))

(defparameter *g*
  (linalg:reshape
   (linalg:linspace -1.0 1.0 (* 16384 384) :element-type 'single-float)
   '(16384 384)))

(defparameter *old*
  (linalg:reshape
   (linalg:linspace 0.0 1.0 (* 16384 384) :element-type 'single-float)
   '(16384 384)))

(defparameter *w*
  (linalg:linspace 0.5 1.5 384 :element-type 'single-float))

(defparameter *b*
  (linalg:linspace -0.25 0.25 384 :element-type 'single-float))

(defparameter *labels* nil)

(defparameter *best* (make-hash-table :test 'equal))

(defun force (a)
  ;; One element read brings a lazy result home; the whole array comes with it. A member
  ;; answering two arrays has both forced, because both are results.
  (if (listp a)
      (dolist (e a) (aref e 0 0))
      (aref a 0 0)))

(defun run (label force-p thunk)
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
   ;; The pair at issue.
   (cons "affine forward"
         (lambda () (linalg::%la-layer-norm-affine *x* *w* *b* *eps*)))
   (cons "affine adjoint (old = nil)"
         (lambda () (linalg::%la-layer-norm-affine-grad *g* *x* *w* *eps* nil)))
   (cons "affine adjoint (onto old)"
         (lambda () (linalg::%la-layer-norm-affine-grad *g* *x* *w* *eps* *old*)))
   ;; What the decline lands on, member by member, for scale: the plain pair the module
   ;; ran before todo-634, and the two broadcasts the affine adds around the forward.
   (cons "plain normalization"
         (lambda () (linalg::%la-layer-norm *x* *eps*)))
   (cons "plain adjoint (old = nil)"
         (lambda () (linalg::%la-layer-norm-grad *g* *x* *eps* nil)))
   (cons "plain adjoint, two results (todo-644)"
         (lambda () (linalg::%la-layer-norm-grad-norm *g* *x* *eps* nil)))
   (cons "one broadcast multiply by the (len) weight"
         (lambda () (linalg:mul *x* *w*)))
   (cons "one zip multiply over the activation"
         (lambda () (linalg:mul *x* *g*)))))

(dotimes (warm 3)
  (dolist (c *cases*) (force (funcall (cdr c)))))

(dotimes (round *rounds*)
  (dolist (c *cases*) (run (car c) t (cdr c)))
  (dolist (c *cases*) (run (car c) nil (cdr c))))

(format t "~%~a reps, best of ~a rounds, ms per call, (~a ~a) f32~%"
        *reps* *rounds* *rows* *len*)
(format t "~%  forced home (the call plus the download)~%")
(dolist (key (reverse *labels*))
  (when (cadr key) (format t "    ~,3f  ~a~%" (gethash key *best*) (car key))))
(format t "~%  enqueue only (no result read)~%")
(dolist (key (reverse *labels*))
  (unless (cadr key) (format t "    ~,3f  ~a~%" (gethash key *best*) (car key))))

;; The claim any acceptance rests on: the fused member is the chain it replaces, bit for
;; bit. The oracle is the composition spelled here, which is the defun's own -- so this
;; line is trivially true while the member is declined and is the whole assertion once it
;; is not.
(let* ((fused (linalg::%la-layer-norm-affine *x* *w* *b* *eps*))
       (chain (linalg:add (linalg:mul (linalg::%la-layer-norm *x* *eps*) *w*) *b*)))
  (format t "~%forward == the chain everywhere: ~a~%"
          (= 1.0 (linalg:amin (linalg:equal fused chain)))))

(let* ((r (linalg::%la-layer-norm-affine-grad *g* *x* *w* *eps* *old*))
       (q (linalg::%la-layer-norm-grad-norm (linalg:mul *g* *w*) *x* *eps* *old*)))
  (format t "adjoint dx == the chain everywhere: ~a~%"
          (= 1.0 (linalg:amin (linalg:equal (car r) (car q)))))
  (format t "adjoint g*norm == the chain everywhere: ~a~%"
          (= 1.0
             (linalg:amin
              (linalg:equal (car (cdr r)) (linalg:mul *g* (car (cdr q))))))))
