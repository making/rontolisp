;; The per-member DEVICE cost of the chains the fused tier left composed (todo-629's
;; first question), at the book's shapes and BATCH 64: the attention scale and the mask
;; around each softmax, log-softmax over the logits, layer-norm's affine, and the GELU
;; adjoint's two libm calls. Same method as fusion-baseline.lisp -- a marker launch, then
;; *reps* synced calls, read back by fusion-segments.py -- and the same warning: wall time
;; here measures the allocator, not the kernel.
;;
;;   attention scores, per head   (64 256 256)  =  4194304  -- the scale and the mask
;;   the logits                   (16384 3038)  = 49774592  -- log-softmax
;;   activations                  (64 256 384)  =  6291456  -- layer-norm's affine
;;   feed-forward                 (64 256 1536) = 25165824  -- the GELU adjoint
;;
;;   JAR=../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
;;   java -jar $JAR chains-baseline.lisp -o Cb.class --gpu --simd
;;   nsys profile -t cuda -o cb --force-overwrite true \
;;     java -Xmx24g --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED Cb > cb.txt
;;   nsys export --type sqlite -o cb.sqlite --force-overwrite true cb.nsys-rep
;;   python3 fusion-segments.py cb.sqlite cb.txt
;;
;; Not project code: a probe, like everything else in this directory.

(defparameter *reps* 5)

(defun sync (r)
  (let ((a (if (torch:tensorp r) (torch:data r) r)))
    (if (numberp a) a (linalg::%la-sum-squares a 0.0))))

(defun marker () (sync (linalg:rand 1234567 :element-type 'single-float)))

(defmacro bench (label form)
  `(progn
     (marker)
     (dotimes (i *reps*) (sync ,form))
     (format t "~a~%" ,label)))

(defun ramp (n shape)
  (linalg:reshape (linalg:linspace -3.0 3.0 n :element-type 'single-float)
                  shape))

;; --- the attention scale and the mask ---------------------------------------
;; The scalar tier is offered only over a RESIDENT operand, so the two arrays are the
;; results of a device member (linalg:exp over the ramp) rather than host arrays -- which
;; is what they are in the training step, where the score is a matmul's result.

(let* ((x (linalg:exp (ramp 4194304 '(64 256 256))))
       (g (linalg:exp (ramp 4194304 '(64 256 256))))
       (m (torch:subsequent-mask 256))
       (neg (/ -1.0 0.0)))
  (bench "scale (64 256 256): div by 8.0 (scal, op div)" (linalg:div x 8.0))
  (bench "scale (64 256 256): mul by 0.125 (scal, op mul)" (linalg:mul x 0.125))
  (bench "scale (64 256 256): div by 9.79 (scal, op div, not a power of two)"
         (linalg:div x 9.797958971132712))
  (bench "mask (64 256 256): where m -inf x (forward)" (linalg:where m neg x))
  (bench "mask (64 256 256): where m 0.0 g (adjoint)" (linalg:where m 0.0 g))
  (bench "scale+mask (64 256 256): softmax :axis -1 for reference"
         (linalg:softmax x :axis -1)))

;; --- log-softmax over the logits --------------------------------------------

(let* ((x (ramp 49774592 '(16384 3038)))
       (g (ramp 49774592 '(16384 3038)))
       (mx (linalg:amax x :axis 1 :keepdims t))
       (s (linalg:sub x mx))
       (e (linalg:exp s))
       (d (linalg:sum e :axis 1 :keepdims t))
       (out (linalg:log-softmax x :axis 1))
       (tot (linalg:sum g :axis 1 :keepdims t)))
  (bench "log-softmax (16384 3038): amax :axis 1 :keepdims"
         (linalg:amax x :axis 1 :keepdims t))
  (bench "log-softmax (16384 3038): sub bcast" (linalg:sub x mx))
  (bench "log-softmax (16384 3038): exp" (linalg:exp s))
  (bench "log-softmax (16384 3038): sum :axis 1 :keepdims"
         (linalg:sum e :axis 1 :keepdims t))
  (bench "log-softmax (16384 3038): sub bcast of the log" (linalg:sub s (linalg:log d)))
  (bench "log-softmax (16384 3038): linalg:log-softmax :axis 1 (the chain)"
         (linalg:log-softmax x :axis 1))
  (bench "log-softmax (16384 3038): adjoint sum :axis 1 :keepdims"
         (linalg:sum g :axis 1 :keepdims t))
  (bench "log-softmax (16384 3038): adjoint exp of the output" (linalg:exp out))
  (bench "log-softmax (16384 3038): adjoint mul bcast" (linalg:mul e tot))
  (bench "log-softmax (16384 3038): adjoint zip sub" (linalg:sub g e))
  (bench "log-softmax (16384 3038): the adjoint (sum, exp, mul, sub)"
         (linalg:sub g
          (linalg:mul (linalg:exp out) (linalg:sum g :axis 1 :keepdims t)))))

;; --- layer-norm's affine ------------------------------------------------------

(let* ((x (ramp 6291456 '(64 256 384)))
       (g (ramp 6291456 '(64 256 384)))
       (norm (linalg::%la-layer-norm x 1.0e-5))
       (w (linalg:linspace 0.5 1.5 384 :element-type 'single-float))
       (b (linalg:linspace 0.0 0.1 384 :element-type 'single-float)))
  (bench "affine (64 256 384): mul bcast (384) -- forward weight"
         (linalg:mul norm w))
  (bench "affine (64 256 384): add bcast (384) -- forward bias"
         (linalg:add norm b))
  (bench "affine (64 256 384): mul bcast (384) -- adjoint g * weight"
         (linalg:mul g w))
  (bench "affine (64 256 384): zip mul -- adjoint g * norm" (linalg:mul g norm))
  (bench "affine (64 256 384): sum :axis 0 -> (256 384)" (linalg:sum g :axis 0))
  (bench "affine (64 256 384): the whole normalization for reference"
         (linalg::%la-layer-norm x 1.0e-5))
  (let ((tx (torch:tensor x :requires-grad t :element-type nil))
        (ln (torch:layer-norm 384)))
    (bench "affine (64 256 384): torch:layer-norm forward (no grad)"
           (let ((torch::*grad-enabled* nil)) (torch:forward ln tx)))))

;; --- the GELU adjoint's two libm calls ----------------------------------------

(let* ((x (ramp 25165824 '(64 256 1536)))
       (g (ramp 25165824 '(64 256 1536))))
  (bench "gelu (64 256 1536): %la-gelu forward" (linalg::%la-gelu x))
  (bench "gelu (64 256 1536): %la-gelu-grad, no old" (linalg::%la-gelu-grad g x nil))
  (bench "gelu (64 256 1536): erf alone" (linalg:erf x))
  (bench "gelu (64 256 1536): exp alone" (linalg:exp x))
  (bench "gelu (64 256 1536): zip mul (three-array bandwidth floor)"
         (linalg:mul g x)))
