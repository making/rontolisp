;; The per-member DEVICE cost of the fusible compositions at the book's shapes (todo-499's
;; first question): what does each `linalg:` member of a softmax / layer-norm / GELU /
;; dropout chain cost on the device, what does the whole chain cost through the tape, and
;; therefore what would a single-pass kernel buy?
;;
;; The figures are KERNEL time, not wall time: this program is run under nsys and
;; `fusion-segments.py` reads the trace back. Each bench opens with a MARKER launch
;; (`linalg:rand` over 1234567 elements -- the one `rng_fill` in the trace with a 4823-block
;; grid) and then runs its form *reps* times, each call closed by a sync (`%la-sum-squares`
;; over the result: 8 KB of partials down, and a wait for the queue), so the trace between
;; two markers is exactly *reps* calls of one form plus *reps* `sumsq` kernels the script
;; discounts. Wall time is useless here: a call that allocates a fresh 100 MB result each
;; rep spends its host time in the allocator, not in the kernel, and the clock has 1 ms
;; resolution.
;;
;; The shapes are the chapter-3 GPT at the book's configuration (`d_model` 384, block 256,
;; 6 heads of 64, batch 64, feed-forward 1536):
;;   attention scores, per head  (64 256 256)  =  4194304 elements -- softmax's array
;;   activations                 (64 256 384)  =  6291456 elements -- layer-norm's, dropout's
;;   feed-forward                 (64 256 1536) = 25165824 elements -- GELU's
;;
;; Every member is called through a LITERAL call form (the compiled backends intercept at
;; the call site). Run it compiled, with the flags of the training run:
;;
;;   JAR=../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
;;   java -jar $JAR fusion-baseline.lisp -o Fb.class --gpu --simd
;;   nsys profile -t cuda -o fb --force-overwrite true \
;;     java -Xmx12g --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED Fb > fb.txt
;;   nsys export --type sqlite -o fb.sqlite --force-overwrite true fb.nsys-rep
;;   python3 fusion-segments.py fb.sqlite fb.txt
;;
;; Not project code: a probe, like everything else in this directory.

(defparameter *reps* 5)

(defun sync (r)
  ;; Waits for everything queued ahead of r: a device member over a resident operand
  ;; whose answer is a host scalar.
  (let ((a (if (torch:tensorp r) (torch:data r) r)))
    (if (numberp a) a (linalg::%la-sum-squares a 0.0))))

(defun marker () (sync (linalg:rand 1234567 :element-type 'single-float)))

(defmacro bench (label form)
  ;; A marker, then *reps* synced calls; the label in output order is the segment's name.
  `(progn
     (marker)
     (dotimes (i *reps*) (sync ,form))
     (format t "~a~%" ,label)))

(defun ramp (n shape)
  (linalg:reshape (linalg:linspace -3.0 3.0 n :element-type 'single-float)
                  shape))

(defun sum3 (tn)
  ;; A scalar loss whose folds run on the device: axis by axis, the last (64 -> 1)
  ;; declining to the host over 64 elements.
  (torch:sum (torch:sum (torch:sum tn :axis 2) :axis 1) :axis 0))

(defun bwd (tn form-fn)
  ;; Forward + backward of (form-fn tn) under sum3, syncing on tn's gradient.
  (setf (torch::%t-grad tn) nil)
  (torch:backward (sum3 (funcall form-fn tn)))
  (torch:grad tn))

(let* ((x (ramp 4194304 '(64 256 256)))
       (m (linalg:amax x :axis 2 :keepdims t))
       (s (linalg:sub x m))
       (e (linalg:exp s))
       (d (linalg:sum e :axis 2 :keepdims t))
       (out (linalg:div e d))
       (g (ramp 4194304 '(64 256 256))))
  (bench "softmax (64 256 256): amax :axis 2 :keepdims"
         (linalg:amax x :axis 2 :keepdims t))
  (bench "softmax (64 256 256): sub bcast" (linalg:sub x m))
  (bench "softmax (64 256 256): exp" (linalg:exp s))
  (bench "softmax (64 256 256): sum :axis 2 :keepdims"
         (linalg:sum e :axis 2 :keepdims t))
  (bench "softmax (64 256 256): div bcast" (linalg:div e d))
  (bench "softmax (64 256 256): linalg:softmax :axis -1"
         (linalg:softmax x :axis -1))
  (bench "softmax (64 256 256): zip mul" (linalg:mul g out))
  (bench "softmax (64 256 256): the adjoint (mul, sum, sub, mul)"
         (linalg:mul out
          (linalg:sub g (linalg:sum (linalg:mul g out) :axis 2 :keepdims t))))
  (let ((tx (torch:tensor x :requires-grad t :element-type nil)))
    (bench "softmax (64 256 256): torch forward (no grad)"
           (let ((torch::*grad-enabled* nil)) (torch:softmax tx :axis -1)))
    (bench "softmax (64 256 256): sum3 + backward of the input alone"
           (bwd tx (lambda (tn) tn)))
    (bench "softmax (64 256 256): torch forward + backward"
           (bwd tx (lambda (tn) (torch:softmax tn :axis -1))))))

(let* ((x (ramp 6291456 '(64 256 384)))
       (ln (torch:layer-norm 384))
       (mu (linalg:mean x :axis 2 :keepdims t))
       (dev (linalg:sub x mu))
       (w (linalg:linspace 0.5 1.5 384 :element-type 'single-float)))
  (bench "layer-norm (64 256 384): sum :axis 2 :keepdims"
         (linalg:sum x :axis 2 :keepdims t))
  (bench "layer-norm (64 256 384): sum :axis 0 -> (256 384)"
         (linalg:sum x :axis 0))
  (bench "layer-norm (64 256 384): mean :axis 2 :keepdims"
         (linalg:mean x :axis 2 :keepdims t))
  (bench "layer-norm (64 256 384): var :axis 2 :keepdims"
         (linalg:var x :axis 2 :keepdims t))
  (bench "layer-norm (64 256 384): sub bcast (64 256 1)" (linalg:sub x mu))
  (bench "layer-norm (64 256 384): mul bcast (384)" (linalg:mul dev w))
  (bench "layer-norm (64 256 384): zip mul" (linalg:mul dev dev))
  (let ((tx (torch:tensor x :requires-grad t :element-type nil)))
    (bench "layer-norm (64 256 384): torch forward (no grad)"
           (let ((torch::*grad-enabled* nil)) (torch:forward ln tx)))
    (bench "layer-norm (64 256 384): sum3 + backward of the input alone"
           (bwd tx (lambda (tn) tn)))
    (bench "layer-norm (64 256 384): torch forward + backward"
           (bwd tx (lambda (tn) (torch:forward ln tn))))))

(let* ((x (ramp 25165824 '(64 256 1536))))
  (bench "gelu (64 256 1536): erf" (linalg:erf x))
  (bench "gelu (64 256 1536): scal mul 0.5" (linalg:mul x 0.5))
  (bench "gelu (64 256 1536): zip mul" (linalg:mul x x))
  (let ((tx (torch:tensor x :requires-grad t :element-type nil)))
    (bench "gelu (64 256 1536): torch forward (no grad)"
           (let ((torch::*grad-enabled* nil)) (torch:gelu tx)))
    (bench "gelu (64 256 1536): sum3 + backward of the input alone"
           (bwd tx (lambda (tn) tn)))
    (bench "gelu (64 256 1536): torch forward + backward"
           (bwd tx (lambda (tn) (torch:gelu tn))))))

(let* ((x (ramp 6291456 '(64 256 384)))
       (drop (torch:train (torch:dropout 0.1)))
       (mask (linalg:rand '(64 256 384) :element-type 'single-float)))
  (bench "dropout (64 256 384): rand"
         (linalg:rand '(64 256 384) :element-type 'single-float))
  (bench "dropout (64 256 384): greater 0.1" (linalg:greater mask 0.1))
  (bench "dropout (64 256 384): div 0.9" (linalg:div mask 0.9))
  (bench "dropout (64 256 384): the mask chain (rand, greater, div)"
         (linalg:div (linalg:greater
                      (linalg:rand '(64 256 384) :element-type 'single-float)
                      0.1) 0.9))
  (let ((tx (torch:tensor x :requires-grad t :element-type nil)))
    (bench "dropout (64 256 384): torch forward (no grad)"
           (let ((torch::*grad-enabled* nil)) (torch:forward drop tx)))
    (bench "dropout (64 256 384): sum3 + backward of the input alone"
           (bwd tx (lambda (tn) tn)))
    (bench "dropout (64 256 384): torch forward + backward"
           (bwd tx (lambda (tn) (torch:forward drop tn))))))

(let* ((k (ramp 1048576 '(64 256 64))) (a (ramp 6291456 '(64 256 384))))
  (bench "transpose '(0 2 1) (64 256 64)" (linalg:transpose k '(0 2 1)))
  (bench "transpose '(0 2 1) (64 256 384)" (linalg:transpose a '(0 2 1))))
