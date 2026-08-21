;; The CPU side of phase 3's question: what does a training step still spend its time on
;; once phases 4a and 4b have taken the products and the transcendentals?
;;
;; A JFR profile of `train-gpt-soseki.lisp` at the notebook's shapes under `--gpu --simd`
;; names three `--simd` kernels and nothing else: `laBcastFF` (a BROADCAST binary
;; element-wise op), `laSumAxis`/`laFoldAxis` (an AXIS reduction) and `laTransposeAxes`.
;; All three are SCALAR ODOMETER walks in every `--simd` backend -- `.kb/linalg-simd.md`
;; says so explicitly -- where the members phase 4b measured (`add`, `sqrt`, ...) are LANE
;; loops. So 4b's flat "one machine instruction per element" figure is the wrong CPU
;; column for them, and this file measures the right one, at the shapes the transformer
;; actually produces.
;;
;; The shapes are the notebook's: batch 4, block 256, n-embd 384, n-head 2 (so d-k 192).
;;   attention scores  (4 256 256)  = 262144 elements   -- softmax's own array
;;   activations       (4 256 384)  = 393216 elements   -- layer-norm's
;;   feed-forward      (4 256 1536) = 1572864 elements  -- gelu's
;;   a key/query       (4 256 192)  = 196608 elements   -- the transpose's
;;
;; Every member is called through a LITERAL call form, never through `funcall`: on the
;; compiled backends the interception is at the call site, so `(funcall #'linalg:sub a b)`
;; would measure the scalar defun and quietly report the wrong baseline.
;;
;;   JAR=../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
;;   java -jar $JAR shaped-baseline.lisp -o Sb.class --simd     # keep the name path-free
;;   java --add-modules jdk.incubator.vector Sb
;;   java -jar $JAR shaped-baseline.lisp -o Sg.class --gpu --simd
;;   java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED Sg
(defmacro bench (label reps form)
  `(progn
     (dotimes (i 50) ,form)
     (let ((best 1.0e30))
       (dotimes (round 3)
         (let ((t0 (get-internal-real-time)))
           (dotimes (i ,reps) ,form)
           (let ((us (/ (* 1000000.0 (- (get-internal-real-time) t0))
                        (* ,reps internal-time-units-per-second))))
             (when (< us best) (setq best us)))))
       (format t "~a ~,1f us/call~%" ,label best))))

(defun warm (etype)
  ;; The device call path is JIT-compiled on its FIRST few thousand calls in a process
  ;; and costs several hundred us/call until it is (`.kb/gpu.md`), which survives
  ;; best-of-three because all three rounds are inside the warm-up. Throw away 3000 calls
  ;; of each kernel family at each width before anything that is quoted; on a run without
  ;; a device this is a few milliseconds of CPU kernel and equally harmless.
  (let* ((x (linalg:reshape (linalg:linspace 0.01 3.0 65536 :element-type etype)
                            '(1024 64)))
         (r (linalg:sum x :axis 1 :keepdims t)))
    (dotimes (i 3000)
      (linalg:sub x r)
      (linalg:sum x :axis 1 :keepdims t)
      (linalg:transpose x '(1 0)))))

(defun run (tag etype)
  (warm etype)
  ;; The softmax chain, over one attention-score array.
  (let* ((x (linalg:reshape (linalg:linspace 0.01 3.0 262144 :element-type etype)
                            '(4 256 256)))
         (m (linalg:amax x :axis 2 :keepdims t))
         (s (linalg:sub x m))
         (d (linalg:sum s :axis 2 :keepdims t)))
    (bench (concatenate 'string tag " amax:axis (4 256 256)") 400
           (linalg:amax x :axis 2 :keepdims t))
    (bench (concatenate 'string tag " sum:axis (4 256 256)") 400
           (linalg:sum s :axis 2 :keepdims t))
    (bench (concatenate 'string tag " bcast sub (4 256 256)-(4 256 1)") 400
           (linalg:sub x m))
    (bench (concatenate 'string tag " bcast div (4 256 256)/(4 256 1)") 400
           (linalg:div s d))
    (bench (concatenate 'string tag " exp (4 256 256)") 400 (linalg:exp s))
    (bench (concatenate 'string tag " softmax:axis (4 256 256)") 400
           (linalg:softmax x :axis -1))
    (bench (concatenate 'string tag " log-softmax:axis (4 256 256)") 400
           (linalg:log-softmax x :axis -1)))
  ;; The layer-norm chain, over one activation array.
  (let* ((x (linalg:reshape (linalg:linspace 0.01 3.0 393216 :element-type etype)
                            '(4 256 384)))
         (g (linalg:linspace 0.5 1.5 384 :element-type etype))
         (mu (linalg:mean x :axis 2 :keepdims t))
         (dev (linalg:sub x mu)))
    (bench (concatenate 'string tag " mean:axis (4 256 384)") 400
           (linalg:mean x :axis 2 :keepdims t))
    (bench (concatenate 'string tag " var:axis (4 256 384)") 400
           (linalg:var x :axis 2 :keepdims t))
    (bench (concatenate 'string tag " bcast sub (4 256 384)-(4 256 1)") 400
           (linalg:sub x mu))
    (bench (concatenate 'string tag " bcast mul (4 256 384)*(384)") 400
           (linalg:mul dev g))
    (bench (concatenate 'string tag " same-shape sub (4 256 384)") 400
           (linalg:sub x dev))
    (bench (concatenate 'string tag " sum:axis0 (4 256 384)") 400
           (linalg:sum x :axis 0)))
  ;; The transpose behind every attention head, and the feed-forward's own shape.
  (let ((k (linalg:reshape (linalg:linspace 0.01 3.0 196608 :element-type etype)
                           '(4 256 192)))
        (h (linalg:reshape (linalg:linspace 0.01 3.0 1572864 :element-type etype)
                           '(4 256 1536))))
    (bench (concatenate 'string tag " transpose '(0 2 1) (4 256 192)") 400
           (linalg:transpose k '(0 2 1)))
    (bench (concatenate 'string tag " erf (4 256 1536)") 20 (linalg:erf h))
    (bench (concatenate 'string tag " same-shape mul (4 256 1536)") 100
           (linalg:mul h h))))

(run "f64" 'double-float)

(run "f32" 'single-float)

(defun sweep (tag etype)
  (warm etype)
  ;; The threshold sweep, shape for shape with StridedCrossover.java's: an (n/64 64)
  ;; array against its own (n/64 1) row reduction, the sum along its inner axis, and its
  ;; transpose. Pair the two tables row by row.
  (dolist (n '(4096 16384 32768 65536 131072 262144 1048576))
    (let* ((rows (floor n 64))
           (x (linalg:reshape (linalg:linspace 0.01 3.0 n :element-type etype)
                              (list rows 64)))
           (r (linalg:sum x :axis 1 :keepdims t))
           (reps (if (<= n 65536) 2000 200)))
      (bench (format nil "~a bcast sub (~d 64)-(~d 1)" tag rows rows) reps
             (linalg:sub x r))
      (bench (format nil "~a fold sum axis 1 (~d 64)" tag rows) reps
             (linalg:sum x :axis 1 :keepdims t))
      (bench (format nil "~a transpose (1 0) (~d 64)" tag rows) reps
             (linalg:transpose x '(1 0))))))

(sweep "f64" 'double-float)

(sweep "f32" 'single-float)

;; The two axis folds the shaped table leaves marginal, measured on their own.
(defun folds (tag etype)
  (warm etype)
  (let ((s (linalg:reshape (linalg:linspace 0.01 3.0 262144 :element-type etype)
                           '(4 256 256)))
        (a (linalg:reshape (linalg:linspace 0.01 3.0 393216 :element-type etype)
                           '(4 256 384))))
    (bench (concatenate 'string tag " sum:axis2 (4 256 384)") 400
           (linalg:sum a :axis 2 :keepdims t))
    (bench (concatenate 'string tag " amax:axis2 (4 256 384)") 400
           (linalg:amax a :axis 2 :keepdims t))
    (bench (concatenate 'string tag " amax:axis2 (4 256 256)") 400
           (linalg:amax s :axis 2 :keepdims t))
    (bench (concatenate 'string tag " amin:axis2 (4 256 256)") 400
           (linalg:amin s :axis 2 :keepdims t))))

(folds "f64" 'double-float)

(folds "f32" 'single-float)
