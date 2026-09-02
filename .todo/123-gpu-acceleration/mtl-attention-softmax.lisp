;;;; The attention head's softmax pair at the book's score shape, per call, on Metal
;;;; (todo-643). The chain `torch:div` -> `torch:masked-fill` -> `torch:softmax` against
;;;; the fused `linalg::%la-scaled-masked-softmax`, and the same for the adjoint, plus the
;;;; PLAIN pair, which must not move (the scaled/masked form is a second entry point).
;;;;
;;;; Wall per call IS device time on this backend: every member is `commit` plus
;;;; `waitUntilCompleted` and nothing overlaps. Best of three rounds, the rounds
;;;; interleaved by the loop below rather than run one label at a time.
;;;;
;;;;   JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
;;;;   java -jar $JAR .todo/123-gpu-acceleration/mtl-attention-softmax.lisp -o Ms.class \
;;;;        --class-name Ms --gpu --simd
;;;;   java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED Ms
;;;;
;;;; Not project code: a probe, like everything else in this directory.

(defparameter *neg-infinity* (/ -1.0 0.0))

(defparameter *reps* 60)

(defparameter *rounds* 3)

(defparameter *scale* (sqrt (* 1.0 64)))

;; The book's score: (64 256 256) per head, f32; the causal mask is (1 256 256) and
;; DOUBLE, which is what `linalg:ones` makes and what the model hands `torch:masked-fill`.

(defparameter *x*
  (linalg:reshape
   (linalg:linspace -3.0 3.0 4194304 :element-type 'single-float) '(64 256 256)))

(defparameter *g*
  (linalg:reshape
   (linalg:linspace -1.0 1.0 4194304 :element-type 'single-float) '(64 256 256)))

(defparameter *mask*
  (linalg:expand-dims (linalg:triu (linalg:ones '(256 256)) :k 1) 0))

(defparameter *mask-f*
  (linalg:expand-dims
   (linalg:triu (linalg:ones '(256 256) :element-type 'single-float) :k 1) 0))

(defparameter *out*
  (linalg::%la-scaled-masked-softmax *x* *scale* *mask* *neg-infinity* -1))

(defparameter *labels* nil)

(defparameter *best* (make-hash-table :test 'equal))

(defun run (label thunk)
  ;; One round of *reps* calls; the best round wins. get-internal-real-time is
  ;; milliseconds here, so the division gives ms per call.
  (unless (gethash label *best*)
    (push label *labels*)
    (setf (gethash label *best*) 1e30))
  (let ((t0 (get-internal-real-time)))
    (dotimes (i *reps*) (funcall thunk))
    (let ((ms (/ (float (- (get-internal-real-time) t0)) *reps*)))
      (when (< ms (gethash label *best*)) (setf (gethash label *best*) ms)))))

(dotimes (warm 5)
  (linalg::%la-scaled-masked-softmax *x* *scale* *mask* *neg-infinity* -1)
  (linalg:softmax *x* :axis -1))

(dotimes (round *rounds*)
  ;; The plain pair: no scale, no mask -- the pre-643 kernels, untouched.
  (run "plain softmax" (lambda () (linalg:softmax *x* :axis -1)))
  (run "plain softmax grad" (lambda () (linalg::%la-softmax-grad *g* *out* -1)))
  ;; The chain, member by member and whole, with the DOUBLE causal mask the model makes.
  (run "chain: div" (lambda () (linalg:div *x* *scale*)))
  (run "chain: where (f64 mask)"
       (lambda () (linalg:where *mask* *neg-infinity* *x*)))
  (run "chain: where (f32 mask)"
       (lambda () (linalg:where *mask-f* *neg-infinity* *x*)))
  (run "chain: softmax over the masked score"
       (lambda () (linalg:softmax (linalg:where *mask* *neg-infinity* *x*) :axis -1)))
  (run "chain forward, whole (f64 mask)"
       (lambda ()
         (linalg:softmax
          (linalg:where *mask* *neg-infinity* (linalg:div *x* *scale*)) :axis -1)))
  (run "chain forward, whole (f32 mask)"
       (lambda ()
         (linalg:softmax
          (linalg:where *mask-f* *neg-infinity* (linalg:div *x* *scale*)) :axis -1)))
  (run "FUSED forward (f64 mask)"
       (lambda ()
         (linalg::%la-scaled-masked-softmax *x* *scale* *mask* *neg-infinity* -1)))
  (run "FUSED forward (f32 mask)"
       (lambda ()
         (linalg::%la-scaled-masked-softmax *x* *scale* *mask-f* *neg-infinity* -1)))
  (run "FUSED forward, scale only"
       (lambda ()
         (linalg::%la-scaled-masked-softmax *x* *scale* nil *neg-infinity* -1)))
  (run "FUSED forward, mask only"
       (lambda ()
         (linalg::%la-scaled-masked-softmax *x* nil *mask* *neg-infinity* -1)))
  ;; The adjoint: the softmax adjoint, the mask's zeroing, the divide.
  (run "chain adjoint, whole (f64 mask)"
       (lambda ()
         (linalg:div (linalg:where *mask* 0.0 (linalg::%la-softmax-grad *g* *out* -1))
                     *scale*)))
  (run "chain adjoint, whole (f32 mask)"
       (lambda ()
         (linalg:div (linalg:where *mask-f* 0.0 (linalg::%la-softmax-grad *g* *out* -1))
                     *scale*)))
  (run "FUSED adjoint (f64 mask)"
       (lambda ()
         (linalg::%la-scaled-masked-softmax-grad *g* *out* -1 *scale* *mask*)))
  (run "FUSED adjoint (f32 mask)"
       (lambda ()
         (linalg::%la-scaled-masked-softmax-grad *g* *out* -1 *scale* *mask-f*))))

(format t "~%~a reps, best of ~a rounds, ms per call~%" *reps* *rounds*)
(dolist (label (reverse *labels*))
  (format t "  ~,3f  ~a~%" (gethash label *best*) label))

;; And the claim the kernels rest on: the fused result IS the chain's, cell for cell.
(let ((a (linalg::%la-scaled-masked-softmax *x* *scale* *mask* *neg-infinity* -1))
      (b (linalg:softmax
          (linalg:where *mask* *neg-infinity* (linalg:div *x* *scale*)) :axis -1)))
  (format t "~%forward max |fused - chain|: ~a~%"
          (linalg:amax (linalg:abs (linalg:sub a b)))))
(let ((a (linalg::%la-scaled-masked-softmax-grad *g* *out* -1 *scale* *mask*))
      (b (linalg:div (linalg:where *mask* 0.0 (linalg::%la-softmax-grad *g* *out* -1))
                     *scale*)))
  (format t "adjoint max |fused - chain|: ~a~%"
          (linalg:amax (linalg:abs (linalg:sub a b)))))
