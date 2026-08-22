;;;; llama2.c in rontolisp: run a Llama 2 model from a llama2.c checkpoint.
;;;;
;;;; This is Andrej Karpathy's run.c (https://github.com/karpathy/llama2.c)
;;;; ported whole: the checkpoint loader, the SentencePiece-style tokenizer with
;;;; its BPE encoder, the transformer forward pass, the temperature / top-p
;;;; sampler and the generate loop. Feed it a checkpoint the C program reads
;;;; and it tells the same stories -- token for token, at temperature 0 and at
;;;; any seed (the sampler is run.c's xorshift, bit for bit). ml/tiny-llm.lisp is
;;;; the arithmetic core of this file with the I/O taken away; this is the whole
;;;; engine.
;;;;
;;;; SETUP
;;;; -----
;;;; stories260K.bin + tok512.bin (the smallest TinyStories model, 1 MB) are
;;;; checked in beside this file. The model the llama2.c README demos,
;;;; stories15M.bin (60 MB) + tokenizer.bin, is one script away:
;;;;
;;;;   ./download-stories15M.sh          # from this directory
;;;;
;;;; RUN IT
;;;; ------
;;;; The knobs are run.c's command-line flags, read from the environment (a
;;;; rontolisp program has no argv yet): LLAMA2_CHECKPOINT (the positional
;;;; checkpoint, default stories15M.bin), LLAMA2_TOKENIZER (-z, default
;;;; tokenizer.bin), LLAMA2_PROMPT (-i), LLAMA2_STEPS (-n, default 256),
;;;; LLAMA2_TEMPERATURE (-t, default 1.0), LLAMA2_TOPP (-p, default 0.9),
;;;; LLAMA2_SEED (-s, default: the clock). From this directory:
;;;;
;;;;   export LLAMA2_PROMPT="Once upon a time" LLAMA2_TEMPERATURE=0
;;;;   rontolisp llama2.lisp --simd                                  # interpreter
;;;;   rontolisp llama2.lisp -o Prog.class --simd && java --add-modules jdk.incubator.vector Prog
;;;;   rontolisp llama2.lisp -o Prog.class --gpu --simd && \
;;;;     java --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector Prog
;;;;   rontolisp llama2.lisp -o llama2.wasm --simd && \
;;;;     wasmtime run -W gc --dir . --env LLAMA2_PROMPT --env LLAMA2_TEMPERATURE llama2.wasm
;;;;   rontolisp llama2.lisp -o llama2.wasm --simd --component && \
;;;;     wasmtime run -W gc --dir . --env LLAMA2_PROMPT --env LLAMA2_TEMPERATURE llama2.wasm
;;;;
;;;; Temperature 0 is greedy decoding: the story is the same on every run, every
;;;; backend and in the C program (the whole 256-token story of the prompt above
;;;; is byte-identical on all of them). At a temperature above 0 the same
;;;; LLAMA2_SEED picks the same story as `run stories15M.bin -s SEED`.
;;;;
;;;; WHERE THE TIME GOES, AND WHY --simd (AND --gpu)
;;;; ------------------------------------------------
;;;; Every matrix multiplies a vector -- decoding is one token at a time -- so
;;;; the whole model is GEMV (`vec:matvec`), 15 million multiply-adds per token
;;;; for stories15M, which `--simd` lowers to CPU vector instructions streaming
;;;; the 60 MB of weights at ~20 GB/s: about 2.4 ms of a 3.0 ms token on the
;;;; JVM. The attention, RoPE and KV-cache loops around the GEMVs compile to
;;;; primitive loops on the JVM (.kb/jvm-typed-loops.md) and are the small rest.
;;;; `--gpu --simd` moves the GEMVs whose matrix is big enough and STAYS
;;;; on the device -- the three feed-forward matrices per layer and the
;;;; classifier head, two thirds of the multiply-adds; the 288x288 projections
;;;; are a tie and stay on the CPU -- from their second token on, which is about
;;;; 1.3x on the JVM class output, with the story unchanged. The KV cache is laid
;;;; out per head as in tiny-llm.lisp: keys row-major (seq-len x head-size),
;;;; values TRANSPOSED (head-size x seq-len), so both halves of attention are a
;;;; GEMV as well (too small for the device, and rewritten every token). Measured
;;;; 2026-08-22 on an NVIDIA GB10 box (stories15M, the 222-token story above):
;;;;
;;;;   JVM        104 tok/s -> 336 tok/s with --simd -> 637 tok/s with --simd --parallel
;;;;                (458 tok/s with --gpu --simd)
;;;;   wasm-GC    0.4 tok/s -> 125 tok/s with --simd
;;;;   interpreter  ~ 15 s/token -> 44 tok/s with --simd (java --add-modules
;;;;                jdk.incubator.vector -jar ...; --gpu buys nothing there, the
;;;;                tree walk around the GEMVs dominates)
;;;;
;;;; Every backend above decodes on ONE thread except --parallel, which runs the
;;;; GEMVs on every core (20 here). On the same box run.c -O2 (one thread) does
;;;; 147 tok/s and the Java Vector API port of run.c (kishida's gist) 312 on one
;;;; thread, 513 as published -- its matmul is an IntStream.parallel(). The
;;;; README's table has the whole comparison.
;;;;
;;;; Without --simd the interpreter runs the scalar vec.lisp definitions, one
;;;; interpreted form per multiply-add: fine for stories260K, not for stories15M.
;;;;
;;;; The checkpoint is 15 million little-endian float32s. They are read with
;;;; `read-sequence` into packed single-float arrays -- one bulk transfer per
;;;; weight matrix, ~0.2 s on every backend.

;;; --- knobs (run.c's flags, from the environment) -----------------------------
(defun env-or (name default)
  (let ((v (uiop:getenv name))) (if (and v (> (length v) 0)) v default)))

(defun env-number (name default)
  (let ((v (uiop:getenv name)))
    (if (and v (> (length v) 0)) (read-from-string v) default)))

(defparameter *checkpoint* (env-or "LLAMA2_CHECKPOINT" "stories15M.bin"))
(defparameter *tokenizer* (env-or "LLAMA2_TOKENIZER" "tokenizer.bin"))
(defparameter *prompt* (env-or "LLAMA2_PROMPT" ""))
(defparameter *steps* (env-number "LLAMA2_STEPS" 256))
(defparameter *temperature* (env-number "LLAMA2_TEMPERATURE" 1.0))
(defparameter *topp* (env-number "LLAMA2_TOPP" 0.9))
(defparameter *seed* (env-number "LLAMA2_SEED" (get-universal-time)))

;;; --- little-endian binary reading -------------------------------------------
;;; The checkpoint is raw little-endian int32 / float32, exactly what run.c
;;; mmaps. Floats go straight into packed single-float arrays: `read-sequence`
;;; over a packed float array reads raw IEEE-754 elements in bulk.

(defun read-i32 (s)
  ;; A little-endian signed 32-bit integer.
  (let* ((b0 (read-byte s))
         (b1 (read-byte s))
         (b2 (read-byte s))
         (b3 (read-byte s))
         (u (+ b0 (* b1 256) (* b2 65536) (* (mod b3 128) 16777216))))
    (if (>= b3 128) (- u 2147483648) u)))

(defun read-f32-vector (s n)
  (let ((v (make-array n :element-type 'single-float :initial-element 0.0)))
    (read-sequence v s)
    v))

(defun read-f32-matrix (s rows cols)
  (let ((m
         (make-array (list rows cols)
                     :element-type 'single-float
                     :initial-element 0.0)))
    (read-sequence m s)
    m))

(defun skip-f32 (s n)
  (read-f32-vector s n)
  nil)

;;; --- the checkpoint: config + weights ---------------------------------------
;;; A model is a plist. Per-layer weights are simple vectors indexed by layer.

(defun load-checkpoint (path)
  (with-open-file (s path :element-type '(unsigned-byte 8))
    (let* ((dim (read-i32 s))
           (hidden (read-i32 s))
           (n-layers (read-i32 s))
           (n-heads (read-i32 s))
           (n-kv-heads (read-i32 s))
           (vocab-signed (read-i32 s))
           (seq-len (read-i32 s))
           ;; a negative vocab size means the classifier is NOT shared with the
           ;; token embedding table
           (shared (> vocab-signed 0))
           (vocab (abs vocab-signed))
           (head-size (floor dim n-heads))
           (kv-dim (* head-size n-kv-heads))
           (per-layer
            (lambda (f)
              (let ((v (make-array n-layers)))
                (dotimes (l n-layers v) (setf (aref v l) (funcall f)))))))
      (let* ((emb (read-f32-matrix s vocab dim))
             (rms-att (funcall per-layer (lambda () (read-f32-vector s dim))))
             (wq (funcall per-layer (lambda () (read-f32-matrix s dim dim))))
             (wk (funcall per-layer (lambda () (read-f32-matrix s kv-dim dim))))
             (wv (funcall per-layer (lambda () (read-f32-matrix s kv-dim dim))))
             (wo (funcall per-layer (lambda () (read-f32-matrix s dim dim))))
             (rms-ffn (funcall per-layer (lambda () (read-f32-vector s dim))))
             (w1 (funcall per-layer (lambda () (read-f32-matrix s hidden dim))))
             (w2 (funcall per-layer (lambda () (read-f32-matrix s dim hidden))))
             (w3 (funcall per-layer (lambda () (read-f32-matrix s hidden dim))))
             (rms-final (read-f32-vector s dim)))
        ;; skip what used to be freq_cis_real / freq_cis_imag (RoPE is computed)
        (skip-f32 s (* seq-len head-size))
        (let ((wcls (if shared emb (read-f32-matrix s vocab dim))))
          (list :dim dim
                :hidden hidden
                :n-layers n-layers
                :n-heads n-heads
                :n-kv-heads n-kv-heads
                :vocab vocab
                :seq-len seq-len
                :head-size head-size
                :kv-dim kv-dim
                :emb emb
                :rms-att rms-att
                :wq wq
                :wk wk
                :wv wv
                :wo wo
                :rms-ffn rms-ffn
                :w1 w1
                :w2 w2
                :w3 w3
                :rms-final rms-final
                :wcls wcls))))))

;;; --- the tokenizer ------------------------------------------------------------
;;; tokenizer.bin: int32 max-token-length, then per token float32 score,
;;; int32 length, UTF-8 bytes. Pieces are decoded to strings (a character is a
;;; code point on every backend); byte-fallback tokens are the strings "<0x00>"
;;; .. "<0xFF>" at ids 3..258.

(defun utf8-decode (bytes n)
  ;; The first n bytes of a byte vector as a string (a character is a code point).
  (let ((chars '()) (i 0))
    (loop while (< i n)
          do
            (let* ((b0 (aref bytes i))
                   (len
                    (cond ((< b0 128) 1) ((< b0 224) 2) ((< b0 240) 3) (t 4)))
                   (cp
                    (cond ((= len 1) b0)
                          ((= len 2) (logand b0 31))
                          ((= len 3) (logand b0 15))
                          (t (logand b0 7)))))
              (dotimes (k (- len 1))
                (setq cp (+ (* cp 64) (logand (aref bytes (+ i 1 k)) 63))))
              (push (code-char cp) chars)
              (setq i (+ i len))))
    (coerce (nreverse chars) 'string)))

(defun load-tokenizer (path vocab)
  ;; -> (list pieces scores index) : piece strings, their scores, and a hash
  ;; table string -> id for the encoder. The scores and lengths are read through
  ;; one-element packed vectors: `read-sequence` over a packed buffer reads raw
  ;; little-endian elements, so this is the checkpoint loader's idiom in small.
  (with-open-file (s path :element-type '(unsigned-byte 8))
    (let* ((u32 (make-array 1 :element-type '(unsigned-byte 32)))
           (f32 (make-array 1 :element-type 'single-float :initial-element 0.0))
           (max-len
            (progn
              (read-sequence u32 s)
              (aref u32 0)))
           (buf (make-array max-len :element-type '(unsigned-byte 8)))
           (pieces (make-array vocab))
           (scores
            (make-array vocab :element-type 'single-float :initial-element 0.0))
           (index (make-hash-table :test 'equal)))
      (dotimes (i vocab)
        (read-sequence f32 s)
        (setf (aref scores i) (aref f32 0))
        (read-sequence u32 s)
        (let ((len (aref u32 0)))
          (read-sequence buf s :end len)
          (let ((piece (utf8-decode buf len)))
            (setf (aref pieces i) piece)
            (unless (gethash piece index) (setf (gethash piece index) i)))))
      (list pieces scores index))))

(defun byte-token-p (piece)
  ;; "<0xNN>" -> the byte NN, else nil.
  (and (= (length piece) 6) (char= (char piece 0) #\<)
       (char= (char piece 1) #\0) (char= (char piece 2) #\x)
       (char= (char piece 5) #\>)
       (parse-integer piece :start 3 :end 5 :radix 16)))

(defun decode-piece (tok prev-token pieces)
  ;; run.c's decode(): after BOS the leading space of a piece is dropped, and a
  ;; byte token stands for its raw byte -- printed only when it is printable
  ;; ASCII or whitespace (safe_printf), since a lone byte of a multi-byte
  ;; sequence has no character to show.
  (let* ((piece (aref pieces tok))
         (piece
          (if (and (= prev-token 1) (> (length piece) 0)
                   (char= (char piece 0) #\Space))
              (subseq piece 1)
              piece))
         (b (byte-token-p piece)))
    (cond ((null b) piece)
          ((or (and (>= b 32) (< b 127)) (= b 10) (= b 9) (= b 13))
           (string (code-char b)))
          (t ""))))

(defun encode (text pieces scores index bos eos)
  ;; run.c's encode(): the dummy-prefix space, one token per character (or its
  ;; byte tokens when the character is not in the vocabulary), then repeatedly
  ;; merge the adjacent pair whose concatenation has the highest score.
  (let ((tokens (make-array (+ (* 2 (length text)) 3) :fill-pointer 0)))
    (when bos (vector-push 1 tokens))
    (when (> (length text) 0) (vector-push (gethash " " index) tokens))
    (dotimes (i (length text))
      (let* ((c (string (char text i))) (id (gethash c index)))
        (if id
            (vector-push id tokens)
            ;; byte fallback: the UTF-8 bytes of the character, ids 3..258
            (let ((cp (char-code (char text i))))
              (dolist (b
                       (cond ((< cp 128) (list cp))
                             ((< cp 2048)
                              (list (+ 192 (ash cp -6)) (+ 128 (logand cp 63))))
                             ((< cp 65536)
                              (list (+ 224 (ash cp -12))
                                    (+ 128 (logand (ash cp -6) 63))
                                    (+ 128 (logand cp 63))))
                             (t (list (+ 240 (ash cp -18))
                                      (+ 128 (logand (ash cp -12) 63))
                                      (+ 128 (logand (ash cp -6) 63))
                                      (+ 128 (logand cp 63))))))
                (vector-push (+ b 3) tokens))))))
    ;; merge loop
    (loop
      (let ((best-score -1e10) (best-id nil) (best-idx nil))
        (dotimes (i (- (fill-pointer tokens) 1))
          (let* ((merged
                  (concatenate 'string (aref pieces (aref tokens i))
                               (aref pieces (aref tokens (+ i 1)))))
                 (id (gethash merged index)))
            (when (and id (> (aref scores id) best-score))
              (setq best-score (aref scores id) best-id id best-idx i))))
        (unless best-idx (return))
        (setf (aref tokens best-idx) best-id)
        (let ((n (fill-pointer tokens)))
          (do ((i (+ best-idx 1) (+ i 1)))
              ((>= i (- n 1)))
            (setf (aref tokens i) (aref tokens (+ i 1))))
          (setf (fill-pointer tokens) (- n 1)))))
    (when eos (vector-push 2 tokens))
    (coerce tokens 'list)))

;;; --- the forward pass ---------------------------------------------------------

(defparameter *eps* 0.00001)

(defun make-state (model)
  ;; The KV cache: per layer, per kv-head, keys (seq-len x hs) row-major and
  ;; values (hs x seq-len) transposed -- see the header. Plus the RoPE tables.
  (let* ((n-layers (getf model :n-layers))
         (n-kv (getf model :n-kv-heads))
         (seq-len (getf model :seq-len))
         (hs (getf model :head-size))
         (kc (make-array (list n-layers n-kv)))
         (vt (make-array (list n-layers n-kv)))
         (half (floor hs 2))
         (rope-cos
          (make-array (list seq-len half)
                      :element-type 'single-float
                      :initial-element 0.0))
         (rope-sin
          (make-array (list seq-len half)
                      :element-type 'single-float
                      :initial-element 0.0)))
    (dotimes (l n-layers)
      (dotimes (h n-kv)
        (setf (aref kc l h)
              (linalg:zeros (list seq-len hs) :element-type 'single-float))
        (setf (aref vt l h)
              (linalg:zeros (list hs seq-len) :element-type 'single-float))))
    ;; RoPE: freq_i = 1 / 10000^(2i/hs), angle = pos * freq_i
    (dotimes (pos seq-len)
      (dotimes (i half)
        (let ((angle (* pos (/ 1.0 (expt 10000.0 (/ (* 2.0 i) hs))))))
          (setf (aref rope-cos pos i) (cos angle))
          (setf (aref rope-sin pos i) (sin angle)))))
    (list :kc kc
          :vt vt
          :rope-cos rope-cos
          :rope-sin rope-sin
          :att (vec:zeros seq-len :element-type 'single-float))))

(defun rmsnorm (x g)
  ;; x / rms(x) * g, the sum of squares being one vec:dot
  (vec:mul (vec:scale x (/ 1.0 (sqrt (+ (/ (vec:dot x x) (length x)) *eps*))))
           g))

(defun rope (v n-heads hs pos rope-cos rope-sin)
  ;; Rotate every head's (even, odd) pairs in place.
  (let ((half (floor hs 2)))
    (dotimes (h n-heads)
      (dotimes (i half)
        (let* ((j (+ (* h hs) (* 2 i)))
               (fcr (aref rope-cos pos i))
               (fci (aref rope-sin pos i))
               (v0 (aref v j))
               (v1 (aref v (+ j 1))))
          (setf (aref v j) (- (* v0 fcr) (* v1 fci)))
          (setf (aref v (+ j 1)) (+ (* v0 fci) (* v1 fcr))))))))

(defun attention (model state l q k v pos)
  ;; Multi-head causal attention over the KV cache; returns the concatenated
  ;; head outputs (dim), before the wo projection.
  (let* ((n-heads (getf model :n-heads))
         (n-kv (getf model :n-kv-heads))
         (kv-mul (floor n-heads n-kv))
         (hs (getf model :head-size))
         (dim (getf model :dim))
         (kc (getf state :kc))
         (vt (getf state :vt))
         (att (getf state :att))
         (out (vec:zeros dim :element-type 'single-float))
         (qh (vec:zeros hs :element-type 'single-float))
         (inv-sqrt-hs (/ 1.0 (sqrt hs))))
    ;; append this position's keys and values to the cache
    (dotimes (h n-kv)
      (let ((kch (aref kc l h)) (vth (aref vt l h)) (base (* h hs)))
        (dotimes (i hs)
          (setf (aref kch pos i) (aref k (+ base i)))
          (setf (aref vth i pos) (aref v (+ base i))))))
    (dotimes (h n-heads)
      (let ((kch (aref kc l (floor h kv-mul)))
            (vth (aref vt l (floor h kv-mul)))
            (base (* h hs)))
        (dotimes (i hs) (setf (aref qh i) (aref q (+ base i))))
        ;; every score at once: (K q) / sqrt(hs); positions past pos stay 0
        (let ((scores (vec:matvec kch qh)) (top -1e30) (z 0.0))
          ;; softmax over 0..pos into att (the rest of att is 0 = the causal mask)
          (dotimes (u (+ pos 1))
            (let ((sc (* (aref scores u) inv-sqrt-hs)))
              (setf (aref att u) sc)
              (when (> sc top) (setq top sc))))
          (dotimes (u (+ pos 1))
            (let ((e (exp (- (aref att u) top))))
              (setf (aref att u) e)
              (setq z (+ z e))))
          (dotimes (u (+ pos 1)) (setf (aref att u) (/ (aref att u) z)))
          ;; the weighted sum of the values: one GEMV over the transposed cache
          (let ((oh (vec:matvec vth att)))
            (dotimes (i hs) (setf (aref out (+ base i)) (aref oh i)))))))
    out))

(defun silu (h)
  ;; x * sigmoid(x) over the whole vector: four vec ufuncs instead of one boxed
  ;; funcall per element
  (vec:mul h
           (vec:reciprocal
            (vec:add (vec:ones (length h) :element-type 'single-float)
                     (vec:exp (vec:negative h))))))

(defun forward (model state token pos)
  ;; -> the logits over the vocabulary
  (let* ((n-heads (getf model :n-heads))
         (n-kv (getf model :n-kv-heads))
         (hs (getf model :head-size))
         (rope-cos (getf state :rope-cos))
         (rope-sin (getf state :rope-sin))
         (x (linalg:row (getf model :emb) token)))
    (dotimes (l (getf model :n-layers))
      ;; attention block
      (let* ((xb (rmsnorm x (aref (getf model :rms-att) l)))
             (q (vec:matvec (aref (getf model :wq) l) xb))
             (k (vec:matvec (aref (getf model :wk) l) xb))
             (v (vec:matvec (aref (getf model :wv) l) xb)))
        (rope q n-heads hs pos rope-cos rope-sin)
        (rope k n-kv hs pos rope-cos rope-sin)
        (setq x
              (vec:add x
                       (vec:matvec (aref (getf model :wo) l)
                                   (attention model state l q k v pos)))))
      ;; feed-forward block: w2 (silu(w1 x) * w3 x)
      (let* ((xb (rmsnorm x (aref (getf model :rms-ffn) l)))
             (h1 (vec:matvec (aref (getf model :w1) l) xb))
             (h3 (vec:matvec (aref (getf model :w3) l) xb)))
        (setq x
              (vec:add x
               (vec:matvec (aref (getf model :w2) l) (vec:mul (silu h1) h3))))))
    (vec:matvec (getf model :wcls) (rmsnorm x (getf model :rms-final)))))

;;; --- the sampler ---------------------------------------------------------------
;;; run.c's xorshift64* generator, bit for bit (64-bit integers are exact on
;;; every backend), so a seed picks the same random stream as the C program.

(defvar *rng-state* 0)
(defparameter +mask64+ 18446744073709551615)

(defun random-u32 ()
  (setq *rng-state* (logxor *rng-state* (ash *rng-state* -12)))
  (setq *rng-state* (logand (logxor *rng-state* (ash *rng-state* 25)) +mask64+))
  (setq *rng-state* (logxor *rng-state* (ash *rng-state* -27)))
  (ash (logand (* *rng-state* 2685821657736338717) +mask64+) -32))

(defun random-f32 ()
  ;; [0, 1)
  (/ (ash (random-u32) -8) 16777216.0))

(defun sample-argmax (logits) (linalg:argmax logits))

(defun softmax-into-list (logits temperature)
  ;; -> a list of (probability . id) over the vocabulary
  (let* ((n (length logits))
         (top -1e30)
         (z 0.0)
         (probs
          (make-array n :element-type 'single-float :initial-element 0.0)))
    (dotimes (i n)
      (let ((v (/ (aref logits i) temperature)))
        (setf (aref probs i) v)
        (when (> v top) (setq top v))))
    (dotimes (i n)
      (let ((e (exp (- (aref probs i) top))))
        (setf (aref probs i) e)
        (setq z (+ z e))))
    (let ((out '()))
      (dotimes (i n (nreverse out)) (push (cons (/ (aref probs i) z) i) out)))))

(defun sample-mult (probs coin)
  ;; sample index from the probabilities (they must sum to 1)
  (let ((cdf 0.0))
    (dolist (p probs (car (last probs)))
      (setq cdf (+ cdf (car p)))
      (when (< coin cdf) (return (cdr p))))))

(defun sample-topp (probs topp coin)
  ;; nucleus sampling: the smallest set of tokens whose cumulative probability
  ;; exceeds topp, sampled from. Tokens below (1 - topp) / (n - 1) cannot be
  ;; part of that set, so they are dropped before the sort (run.c's cutoff).
  (let* ((n (length probs))
         (cutoff (/ (- 1.0 topp) (- n 1)))
         (candidates
          (sort (remove-if (lambda (p) (< (car p) cutoff)) probs)
                (lambda (a b) (> (car a) (car b)))))
         (cum 0.0)
         (kept '()))
    (dolist (p candidates)
      (push p kept)
      (setq cum (+ cum (car p)))
      (when (> cum topp) (return)))
    (setq kept (nreverse kept))
    ;; sample from the truncated list
    (let ((r (* coin cum)) (cdf 0.0))
      (dolist (p kept (cdr (car (last kept))))
        (setq cdf (+ cdf (car p)))
        (when (< r cdf) (return (cdr p)))))))

(defun sample (logits)
  (if (= *temperature* 0)
      (sample-argmax logits)
      (let ((probs (softmax-into-list logits *temperature*))
            (coin (random-f32)))
        (if (or (<= *topp* 0) (>= *topp* 1))
            (sample-mult probs coin)
            (sample-topp probs *topp* coin)))))

;;; --- generate ------------------------------------------------------------------

(defun generate (model state tokenizer prompt steps)
  (let* ((pieces (first tokenizer))
         (prompt-tokens
          (encode prompt pieces (second tokenizer) (third tokenizer) t nil))
         (token (first prompt-tokens))
         (rest (rest prompt-tokens))
         (start nil)
         (pos 0))
    (loop while (< pos steps)
          do
            (let* ((logits (forward model state token pos))
                   (next (if rest (pop rest) (sample logits))))
              (setq pos (+ pos 1))
              ;; the BOS token delimits sequences: stop on it
              (when (= next 1) (return))
              (write-string (decode-piece next token pieces))
              (finish-output)
              (setq token next)
              (unless start (setq start (get-internal-real-time)))))
    (terpri)
    (when (and start (> pos 1))
      (format *error-output* "achieved tok/s: ~,2f~%"
       (/ (* (- pos 1) 1000.0) (max 1 (- (get-internal-real-time) start)))))))

;;; --- main ------------------------------------------------------------------------

(let* ((t0 (get-internal-real-time))
       (model (load-checkpoint *checkpoint*))
       (t1 (get-internal-real-time)))
  (when (or (<= *steps* 0) (> *steps* (getf model :seq-len)))
    (setq *steps* (getf model :seq-len)))
  (let ((tokenizer (load-tokenizer *tokenizer* (getf model :vocab)))
        (state (make-state model)))
    (format *error-output*
            "loaded ~a: dim=~a hidden=~a layers=~a heads=~a kv-heads=~a vocab=~a seq-len=~a in ~a ms (tokenizer + kv cache ~a ms)~%"
            *checkpoint* (getf model :dim) (getf model :hidden)
            (getf model :n-layers) (getf model :n-heads)
            (getf model :n-kv-heads) (getf model :vocab) (getf model :seq-len)
            (- t1 t0) (- (get-internal-real-time) t1))
    (setq *rng-state* (logand *seed* +mask64+))
    (generate model state tokenizer *prompt* *steps*)))
