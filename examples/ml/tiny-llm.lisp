;;;; A transformer decoder -- the arithmetic core of an LLM inference engine --
;;;; and the example where `--simd` earns its keep.
;;;;
;;;; This is llama2's `forward()` with the tokenizer and the weight loader taken
;;;; away: RMSNorm, Q/K/V projections, causal self-attention over a KV cache,
;;;; softmax, the output projection, a SwiGLU feed-forward network, residual
;;;; connections, a classifier head, and greedy (argmax) sampling. Stack more
;;;; layers, load real weights instead of the pseudo-random ones below, and you
;;;; have an inference engine.
;;;;
;;;; WHY IT IS FAST -- the KV cache layout
;;;; -------------------------------------
;;;; Autoregressive decoding is one token at a time, so every matrix here is
;;;; multiplied by a *vector*: it is all GEMV (`vec:matvec`), never GEMM. Nine
;;;; of the ten hot operations per layer are therefore a single `vec:matvec`,
;;;; which `--simd` lowers to CPU vector instructions.
;;;;
;;;; The tenth -- attention -- is only a GEMV if you store the cache correctly,
;;;; and that is the one design decision in this file worth stealing:
;;;;
;;;;   K cache:  row-major, (n-ctx x dim)   -- row t is the key at position t,
;;;;                                           so `(vec:matvec kc q)` computes
;;;;                                           ALL attention scores in one GEMV.
;;;;   V cache:  TRANSPOSED, (dim x n-ctx)  -- row j is the j-th component over
;;;;                                           time, so `(vec:matvec vt a)` is
;;;;                                           the attention-weighted sum of the
;;;;                                           value vectors, again one GEMV.
;;;;
;;;; Store V row-major and that second step becomes a scalar loop over the cache.
;;;; llama2.c makes exactly this choice; so does every fast engine.
;;;;
;;;; DETERMINISM
;;;; -----------
;;;; Weights come from a fixed-seed linear congruential generator, so the model
;;;; is identical on every backend and every run. Only INTEGERS are printed: the
;;;; WASM backend rounds floats to about seven significant digits when printing,
;;;; and its `exp` differs from the JVM's in the low bits, so a float would not
;;;; compare across backends. The generated token ids are a fingerprint of the
;;;; whole computation -- if any kernel were wrong, they would change.
;;;;
;;;; The model is untrained, so the tokens mean nothing. The arithmetic is real.
;;;;
;;;; RUN IT BOTH WAYS
;;;; ----------------
;;;;   rontolisp examples/ml/tiny-llm.lisp                                   # scalar
;;;;   rontolisp examples/ml/tiny-llm.lisp --simd                            # Vector API
;;;;
;;;;   rontolisp examples/ml/tiny-llm.lisp -o llm.wasm         && wasmtime run llm.wasm
;;;;   rontolisp examples/ml/tiny-llm.lisp -o llm.wasm --simd  && wasmtime run llm.wasm
;;;;
;;;; The token ids must not change. The elapsed time should. Measured on an M4
;;;; (decode only, weight init excluded):
;;;;
;;;;   wasm-GC    891 ms  ->    8 ms   with --simd   (114x -- native f32x4)
;;;;   interpreter 11.5 s ->  1.7 s    with --simd   (7x, the native binary)
;;;;
;;;; The JVM backend is the one to be careful with: `--simd` there depends on the
;;;; JVM compiling jdk.incubator.vector down to vector instructions, and a
;;;; single-float GEMV -- which widens every f32 lane to f64 before accumulating --
;;;; is the shape most likely to find an operation a given JVM does not. Across
;;;; the JVMs measured here the same class ranged from a 2.3x speedup to a 15x
;;;; slowdown. Measure before you trust it. See doc/en/guides/simd-acceleration.md.

;;; --- model size -------------------------------------------------------------
;;; dim must be >= 128: below that the JVM and interpreter Vector-API kernels
;;; fall back to a scalar loop (the vector setup costs more than it saves).
(defparameter *vocab* 48)
(defparameter *dim* 256)
(defparameter *hidden* 512)
(defparameter *layers* 2)
(defparameter *n-ctx* 12)
(defparameter *eps* 0.00001)

;;; --- deterministic pseudo-random weights ------------------------------------
;;; The same Lehmer generator deep-digits.lisp uses: every intermediate stays
;;; below 2^23, which fits the WASM backend's i31 integer range, so the weight
;;; stream is identical on all four backends.
(defvar *lcg-state* 7)

(defun lcg-next ()
  (setq *lcg-state* (mod (+ (* *lcg-state* 75) 74) 65537))
  *lcg-state*)

;;; A single-float in [-scale, scale).
(defun lcg-uniform (scale) (* scale (- (/ (mod (lcg-next) 2048) 1024.0) 1.0)))

(defun random-matrix (rows cols scale)
  (let ((m (linalg:zeros (list rows cols) :element-type 'single-float)))
    (dotimes (i rows m)
      (dotimes (j cols) (setf (aref m i j) (lcg-uniform scale))))))

;;; --- one decoder layer ------------------------------------------------------
;;; A layer is a plist of its seven weight matrices, its two RMSNorm gains, and
;;; its own KV cache -- per-layer, exactly as in a real engine.
(defun make-layer ()
  (list :wq (random-matrix *dim* *dim* 0.08)
        :wk (random-matrix *dim* *dim* 0.08)
        :wv (random-matrix *dim* *dim* 0.08)
        :wo (random-matrix *dim* *dim* 0.08)
        :w1 (random-matrix *hidden* *dim* 0.06)
        :w3 (random-matrix *hidden* *dim* 0.06)
        :w2 (random-matrix *dim* *hidden* 0.06)
        :ng1 (vec:ones *dim* :element-type 'single-float)
        :ng2 (vec:ones *dim* :element-type 'single-float)
        :kc (linalg:zeros (list *n-ctx* *dim*) :element-type 'single-float)   ; keys, row-major
        :vt (linalg:zeros (list *dim* *n-ctx*) :element-type 'single-float))) ; values, TRANSPOSED

;;; --- RMSNorm: x / rms(x) * g ------------------------------------------------
;;; vec:dot is the sum of squares -- one accelerated reduction, no loop.
(defun rmsnorm (x g)
  (vec:mul (vec:scale x (/ 1.0 (sqrt (+ (/ (vec:dot x x) *dim*) *eps*)))) g))

;;; --- causal self-attention over the KV cache --------------------------------
;;; Both halves are a GEMV, thanks to the cache layout described at the top.
;;; Positions after `pos` keep a zero attention weight, which is the causal mask:
;;; a zero weight contributes nothing to the `vt` GEMV, so no masking arithmetic
;;; is needed at all.
(defun attention (l x pos)
  (let ((q (vec:matvec (getf l :wq) x))
        (k (vec:matvec (getf l :wk) x))
        (v (vec:matvec (getf l :wv) x))
        (kc (getf l :kc))
        (vt (getf l :vt)))
    ;; append this position's key and value to the cache
    (dotimes (j *dim*)
      (setf (aref kc pos j) (aref k j))
      (setf (aref vt j pos) (aref v j)))
    ;; every attention score at once: scores = (K q) / sqrt(dim)
    (let ((scores (vec:scale (vec:matvec kc q) (/ 1.0 (sqrt *dim*))))
          (w (vec:zeros *n-ctx* :element-type 'single-float))
          (top -1000000.0)
          (z 0.0))
      ;; softmax over positions 0..pos, shifted by the max for stability
      (dotimes (u (+ pos 1))
        (when (> (aref scores u) top) (setq top (aref scores u))))
      (dotimes (u (+ pos 1))
        (let ((e (exp (- (aref scores u) top))))
          (setf (aref w u) e)
          (setq z (+ z e))))
      ;; the weighted sum of the value vectors, then the output projection
      (vec:matvec (getf l :wo) (vec:matvec vt (vec:scale w (/ 1.0 z)))))))

;;; --- SwiGLU feed-forward: w2 (silu(w1 h) * w3 h) ----------------------------
(defun silu (x) (/ x (+ 1.0 (exp (- 0.0 x)))))

(defun feed-forward (l h)
  (vec:matvec (getf l :w2)
              (vec:mul (linalg:emap #'silu (vec:matvec (getf l :w1) h))
                       (vec:matvec (getf l :w3) h))))

;;; --- the layer, with its two residual connections ---------------------------
(defun layer-forward (l x pos)
  (let ((h (vec:add x (attention l (rmsnorm x (getf l :ng1)) pos))))
    (vec:add h (feed-forward l (rmsnorm h (getf l :ng2))))))

;;; --- the model --------------------------------------------------------------
(defparameter *net*
  (let ((ls '()))
    (dotimes (i *layers* (reverse ls)) (setq ls (cons (make-layer) ls)))))
(defparameter *emb* (random-matrix *vocab* *dim* 0.5))
(defparameter *pos-emb* (random-matrix *n-ctx* *dim* 0.1))
(defparameter *ng-final* (vec:ones *dim* :element-type 'single-float))
(defparameter *w-cls* (random-matrix *vocab* *dim* 0.08))

;;; token embedding + learned position embedding
(defun embed (tok pos)
  (let ((x (vec:zeros *dim* :element-type 'single-float)))
    (dotimes (j *dim* x)
      (setf (aref x j) (+ (aref *emb* tok j) (aref *pos-emb* pos j))))))

;;; one full forward pass -> the logits over the vocabulary
(defun forward (tok pos)
  (let ((x (embed tok pos)))
    (dolist (l *net*) (setq x (layer-forward l x pos)))
    (vec:matvec *w-cls* (rmsnorm x *ng-final*))))

;;; --- greedy decode ----------------------------------------------------------
;;; Positions 0..n-1 of the prompt are the prefill; every later position feeds
;;; back the argmax of the previous step. The KV cache is what makes each step
;;; O(dim^2) instead of O(pos * dim^2).
(defparameter *prompt* '(3 14 1 5))

(defun generate ()
  (let ((tok (first *prompt*)) (out '()))
    (dotimes (pos *n-ctx* (reverse out))
      (let ((next (linalg:argmax (forward tok pos))))
        (if (< (+ pos 1) (length *prompt*))
            (setq tok (nth (+ pos 1) *prompt*))
            (progn
              (setq out (cons next out))
              (setq tok next)))))))

;;; --- run --------------------------------------------------------------------
;;; Every count below is an exact integer, so it prints identically everywhere.
(defun gemvs-per-token () (+ (* *layers* 6) 1))

(defun macs-per-token ()
  (+ (* *layers*
        (+ (* 4 *dim* *dim*)     ; wq wk wv wo
           (* 3 *dim* *hidden*)  ; w1 w3 w2
           (* 2 *n-ctx* *dim*))) ; the two attention GEMVs
     (* *vocab* *dim*)))         ; the classifier

(format t
        "tiny-llm: ~a-layer transformer decoder, dim=~a hidden=~a ctx=~a vocab=~a, single-float~%"
        *layers* *dim* *hidden* *n-ctx* *vocab*)
(format t
 "~a GEMVs and ~a multiply-adds per forward pass, nearly all of it vec:matvec~%"
 (gemvs-per-token) (macs-per-token))
(format t "prompt:    ~a~%" *prompt*)

(let* ((start (get-internal-real-time))
       (tokens (generate))
       (elapsed (- (get-internal-real-time) start)))
  (format t "generated: ~a~%" tokens)
  (format t "~a forward passes (~a prompt + ~a generated) in ~a ms~%" *n-ctx*
          (length *prompt*) (length tokens) elapsed)
  (format t
   "(re-run with --simd; the tokens must not change, the time should)~%"))
