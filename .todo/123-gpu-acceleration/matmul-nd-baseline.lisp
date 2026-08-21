;; The rank-3 (stacked) matrix product, warm, at the shapes --gpu's batch threshold is
;; decided by. matmul-baseline-warm.lisp beside this one is the rank-2 twin; this one is
;; what the batched half of .kb/gpu.md quotes.
;;
;; Three rounds of REPS calls each after 300 warm-up calls, and the BEST round is printed.
;; Two warm-up traps, both measured on the GB10 and both worth several fold:
;;   - the device drops to its idle clock (208 MHz against 3003) between small calls, so a
;;     single timed round over-reports;
;;   - the first shapes measured in a process pay for JIT-compiling the whole device path
;;     (~500 us/call for their first few thousand calls, on every round, and it comes in
;;     more than one wave), so FOUR throwaway benches run before anything that is quoted.
;;
;;   java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
;;     -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar \
;;     .todo/123-gpu-acceleration/matmul-nd-baseline.lisp [--simd] [--gpu]
(defun bench (batch n etype label reps)
  (let* ((a (linalg:add (linalg:ones (list batch n n) :element-type etype) 0.5))
         (b (linalg:add (linalg:ones (list batch n n) :element-type etype) 0.25))
         (best 1.0e30))
    (dotimes (i 300) (linalg:matmul a b))
    (dotimes (round 3)
      (let ((t0 (get-internal-real-time)))
        (dotimes (i reps) (linalg:matmul a b))
        (let ((us (/ (* 1000000.0 (- (get-internal-real-time) t0))
                     (* reps internal-time-units-per-second))))
          (when (< us best) (setq best us)))))
    (format t "batch=~d n=~d ~a ~,1f us/call~%" batch n label best)))

(bench 32 16 nil "warmup" 2000)
(bench 32 16 'single-float "warmup" 2000)
(bench 16 64 nil "warmup" 500)
(bench 16 64 'single-float "warmup" 500)

(dolist (shape '((256 8 500) (64 16 1000) (32 24 1000) (16 32 1000) (4 64 1000) (16 64 500)
                 (32 64 200) (4 128 200) (16 128 100) (12 256 50)))
  (bench (first shape) (second shape) nil "f64" (third shape))
  (bench (first shape) (second shape) 'single-float "f32" (third shape)))
