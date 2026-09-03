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
;;;; THE LAYER TABLE
;;;; ---------------
;;;; What is not run.c: the forward pass is a TABLE OF LAYER KINDS rather than
;;;; Llama 2 spelled out. A model is a list of layers, every one of them the same
;;;; residual sandwich -- normalise, mix, add back -- differing only in its kind
;;;; (:attention, :swiglu, or :deltanet -- the Gated DeltaNet recurrence of
;;;; Qwen3.5, in deltanet.lisp beside this file) and in the options beside it:
;;;; QK-norm, the RoPE layout (adjacent pairs, halves, or none at all), a
;;;; partial rotary dim, an output gate, the attention scale, and the
;;;; model-level embedding / residual / logit multipliers. The families
;;;; published since Llama 2 are that same list with different options, so a
;;;; reader of a published checkpoint builds the list from the file's
;;;; architecture name and the forward pass needs no case for it. llama2.c's
;;;; .bin is the row where every option is at its default -- which is why the
;;;; stories are still byte-identical.
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
;;;; The knobs are run.c's own command-line flags, read with
;;;; uiop:command-line-arguments: the positional checkpoint (default
;;;; stories15M.bin), -z the tokenizer (default tokenizer.bin), -i the prompt,
;;;; -n the steps (default 256), -t the temperature (default 1.0), -p top-p
;;;; (default 0.9) and -s the seed (default: the clock). Each one falls back to
;;;; an LLAMA2_* environment variable (LLAMA2_CHECKPOINT, LLAMA2_TOKENIZER,
;;;; LLAMA2_PROMPT, LLAMA2_STEPS, LLAMA2_TEMPERATURE, LLAMA2_TOPP, LLAMA2_SEED)
;;;; for a host that has no command line to give. From this directory -- the
;;;; interpreter takes the program's own arguments after `--`, a compiled
;;;; artifact simply after itself:
;;;;
;;;;   ARGS='stories15M.bin -t 0 -i "Once upon a time"'
;;;;   rontolisp llama2.lisp --simd -- $ARGS                          # interpreter
;;;;   rontolisp llama2.lisp -o Prog.class --simd && \
;;;;     java --add-modules jdk.incubator.vector Prog $ARGS
;;;;   rontolisp llama2.lisp -o Prog.class --gpu --simd && \
;;;;     java --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector Prog $ARGS
;;;;   rontolisp llama2.lisp -o llama2.wasm --simd && \
;;;;     wasmtime run --dir . llama2.wasm $ARGS
;;;;   rontolisp llama2.lisp -o llama2.wasm --simd --component && \
;;;;     wasmtime run --dir . llama2.wasm $ARGS
;;;;
;;;; Temperature 0 is greedy decoding: the story is the same on every run, every
;;;; backend and in the C program (the whole 256-token story of the prompt above
;;;; is byte-identical on all of them). At a temperature above 0 the same seed
;;;; picks the same story as `run stories15M.bin -s SEED`.
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

;;; --- knobs (run.c's flags, then the environment) -----------------------------
;;; `llama2 stories15M.bin -z tokenizer.bin -t 0 -n 40 -i "Once upon a time"`,
;;; the C program's own command line, on every backend. The LLAMA2_* variables
;;; stay as the fallback: a host that hands the program no command line (a
;;; browser shim, an embedder) still has an environment to set.
(defparameter *args* (uiop:command-line-arguments))

(defun env-or (name default)
  (let ((v (uiop:getenv name))) (if (and v (> (length v) 0)) v default)))

(defun env-number (name default)
  (let ((v (uiop:getenv name)))
    (if (and v (> (length v) 0)) (read-from-string v) default)))

(defun flag-value (flag)
  ;; The word after FLAG on the command line, or nil when it is not there (a
  ;; trailing flag with nothing after it counts as absent, as in run.c).
  (do ((rest *args* (cdr rest)))
      ((null (cdr rest)) nil)
    (if (string= (car rest) flag) (return (car (cdr rest))))))

(defun checkpoint-argument ()
  ;; run.c's positional checkpoint: the first argument, when it is not a flag.
  (let ((head (car *args*)))
    (if (and head (> (length head) 0) (not (char= (char head 0) #\-)))
        head
        nil)))

(defun flag-or-env (flag name default)
  (or (flag-value flag) (env-or name default)))

(defun flag-or-env-number (flag name default)
  (let ((v (flag-value flag)))
    (if v (read-from-string v) (env-number name default))))

(defparameter *checkpoint*
  (or (checkpoint-argument) (env-or "LLAMA2_CHECKPOINT" "stories15M.bin")))
(defparameter *tokenizer* (flag-or-env "-z" "LLAMA2_TOKENIZER" "tokenizer.bin"))
(defparameter *prompt* (flag-or-env "-i" "LLAMA2_PROMPT" ""))
(defparameter *steps* (flag-or-env-number "-n" "LLAMA2_STEPS" 256))
(defparameter *temperature* (flag-or-env-number "-t" "LLAMA2_TEMPERATURE" 1.0))
(defparameter *topp* (flag-or-env-number "-p" "LLAMA2_TOPP" 0.9))
(defparameter *seed*
  (flag-or-env-number "-s" "LLAMA2_SEED" (get-universal-time)))

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

;;; --- the model: a table of layer kinds ----------------------------------------
;;; The models published since Llama 2 are Llama 2's skeleton with per-layer and
;;; per-model deltas, so the layer here is a KIND WITH OPTIONS and the model is a
;;; list of them. Every layer is the same residual sandwich
;;;
;;;     x <- x + residual-multiplier * f(rmsnorm(x, the layer's norm), the layer)
;;;
;;; and differs only in what f is -- :attention, :swiglu, or :deltanet (the
;;; gated linear recurrence Qwen3.5 puts in three of every four blocks,
;;; deltanet.lisp); a short convolution when LFM2 arrives -- and in the options
;;; beside it. Llama 2 is :attention then :swiglu per block with every option at
;;; its default, which is what the .bin loader below builds; the table stays a
;;; table by never special-casing that.

(defparameter *eps* 0.00001)

(defun opt (options key default)
  ;; A plist lookup that tells a MISSING key from one whose value is nil --
  ;; (:rope nil) is NoPE, not "unset". The first occurrence wins, so a reader
  ;; overrides an architecture row by consing its own keys in front of it.
  (do ((rest options (cdr (cdr rest))))
      ((null rest) default)
    (if (eq (car rest) key) (return (car (cdr rest))))))

;;; --- the architecture table -----------------------------------------------
;;; general.architecture (GGUF) / model_type (config.json) -> what that family
;;; does differently. Sizes, weights and the file's RoPE layout are NOT here:
;;; they come from the checkpoint, and a reader prepends them to the row.
;;;
;;;   :qk-norm            the checkpoint carries per-layer q_norm / k_norm, and
;;;                       each head's q and k are RMSNormed with them
;;;   :rope-theta         the RoPE base (Llama 2: 10000)
;;;   :rotary-dim         how many of each head's dims rotate (default: all)
;;;   :no-rope-interval   every Nth block (1-based) has no RoPE at all
;;;   :full-attention-interval
;;;                       a hybrid: every Nth block (1-based) is :attention,
;;;                       the others :deltanet (Qwen3.5: 4)
;;;   :eps                the RMSNorm epsilon (default: llama2.c's 1e-5; the
;;;                       checkpoint's rms_norm_eps / layer_norm_rms_epsilon
;;;                       is the value, so a reader passes it)
;;;   :tied               no lm_head: the classifier IS the embedding table
;;;   :scale              the attention scale, when it is not 1/sqrt(head-size)
;;;   :embedding-multiplier / :residual-multiplier / :logit-multiplier
;;;                       Granite's scalars; their VALUES are per-checkpoint
;;;                       (config.json), so a reader passes them, not this table
;;;                       -- and `logits_scaling` is a divisor, so it arrives as
;;;                       :logit-multiplier (/ 1.0 logits_scaling)
;;;
;;; What a READER of each family has to know that is not an option here:
;;;
;;;   - RoPE layout. llama.cpp's converter permutes Q and K only for the
;;;     families whose rope type is "normal" (llama, smollm3, granite -- a GGUF
;;;     of those is :pairs); a qwen3 / qwen35 GGUF keeps HF's layout and is
;;;     :halves, like every safetensors.
;;;   - qwen35's RMSNorm weights are stored as an OFFSET FROM ONE
;;;     (Qwen3_5RMSNorm computes x * (1 + w)): input_layernorm,
;;;     post_attention_layernorm, q_norm, k_norm and the final norm. A GGUF
;;;     already carries 1 + w (the converter adds it); a safetensors reader
;;;     adds the 1. The Gated DeltaNet's own norm.weight is NOT offset in
;;;     either file (deltanet.lisp).
;;;   - qwen35's q_proj is [heads x (query | gate), dim]: each head's
;;;     head-size query rows are followed by its head-size gate rows, in the
;;;     GGUF (attn_q) exactly as in HF. Both readers split it into :wq and
;;;     :attn-gate.
;;;   - qwen35's rotary dim is head_dim * partial_rotary_factor (256 * 0.25
;;;     = 64 for every published member); its MRoPE sections [11 11 10]
;;;     are the vision model's -- for text every position triple is one
;;;     number and it reduces to 1-D RoPE over the 32 frequencies.
(defparameter *architectures*
  (list (list "llama") ; Llama 2, TinyLlama, and karpathy's stories*.bin
        (list "qwen3" :qk-norm t :rope-theta 1000000.0 :eps 0.000001 :tied t)
        ;; Qwen3.5 / 3.6 / 3.8 dense: 3 of 4 blocks Gated DeltaNet, the 4th
        ;; gated attention (head_dim 256, GQA) with QK-norm and partial RoPE
        (list "qwen35"
              :full-attention-interval 4
              :qk-norm t
              :rotary-dim 64
              :rope-theta 10000000.0
              :eps 0.000001
              :tied t)
        (list "smollm3" :no-rope-interval 4 :rope-theta 5000000.0 :tied t)
        (list "granite" :rope-theta 10000000.0)))

(defun architecture (name)
  ;; The row for NAME, or an error naming the architectures there are.
  (do ((rows *architectures* (cdr rows)))
      ((null rows) (error "unsupported architecture: ~a" name))
    (if (string= (car (car rows)) name) (return (cdr (car rows))))))

;;; --- building the layer list ------------------------------------------------

;; the :deltanet kind: deltanet-layer, deltanet-state, deltanet-forward
(load "deltanet.lisp")

(defun layer-weight (weights key l)
  ;; The layer-L entry of an OPTIONAL per-layer weight vector (q_norm and the
  ;; attention gate are absent in most checkpoints).
  (let ((v (getf weights key))) (if v (aref v l) nil)))

(defun attention-layer (weights options head-size l cache)
  ;; One attention mixer. Everything a family varies is decided here, once, so
  ;; the forward pass reads a value rather than asking what kind of model it is.
  (let ((interval (opt options :no-rope-interval nil)))
    (list :kind :attention
          ;; which KV-cache slot is this layer's (a hybrid's mixers do not all
          ;; have one, so it is not the layer's index)
          :cache cache
          :norm (aref (getf weights :rms-att) l)
          :wq (aref (getf weights :wq) l)
          :wk (aref (getf weights :wk) l)
          :wv (aref (getf weights :wv) l)
          :wo (aref (getf weights :wo) l)
          ;; QK-norm: RMSNorm over each head's own dims, one weight vector of
          ;; head-size shared by every head (Qwen3's q_norm / k_norm)
          :q-norm (layer-weight weights :q-norm l)
          :k-norm (layer-weight weights :k-norm l)
          ;; an output gate over the head outputs, before wo (gated attention)
          :gate (layer-weight weights :attn-gate l)
          ;; :pairs rotates each head's (2i, 2i+1) -- llama2.c's layout, and a
          ;; GGUF converted by llama.cpp, which permutes Q and K to get it.
          ;; :halves rotates (i, i + rotary-dim/2) -- HF's rotate_half, what a
          ;; safetensors file holds. nil is NoPE.
          :rope (if (and interval (= 0 (mod (+ l 1) interval)))
                    nil
                    (opt options :rope :pairs))
          :rotary-dim (opt options :rotary-dim head-size)
          ;; Granite replaces 1/sqrt(head-size) with a constant of its own
          :scale (opt options :scale (/ 1.0 (sqrt head-size))))))

(defun swiglu-layer (weights l)
  ;; The feed-forward half of a block: w2 (silu(w1 x) * w3 x).
  (list :kind :swiglu
        :norm (aref (getf weights :rms-ffn) l)
        :w1 (aref (getf weights :w1) l)
        :w2 (aref (getf weights :w2) l)
        :w3 (aref (getf weights :w3) l)))

(defun transformer-layers (n-layers head-size weights options)
  ;; The layer list of a Llama-2-shaped model: per block a token mixer and a
  ;; feed-forward. WEIGHTS is the plist of per-layer simple vectors every loader
  ;; builds -- :rms-att :wq :wk :wv :wo (:q-norm :k-norm :attn-gate optional)
  ;; :rms-ffn :w1 :w2 :w3, plus the :ssm-* vectors deltanet.lisp lists for the
  ;; blocks a hybrid's :full-attention-interval makes :deltanet (the entries
  ;; of the other kind's vectors are nil there) -- and OPTIONS an architecture
  ;; row with the reader's own keys in front of it.
  (let ((layers '())
        (cache 0)
        (slot 0)
        (interval (opt options :full-attention-interval nil)))
    (dotimes (l n-layers)
      (if (or (null interval) (= 0 (mod (+ l 1) interval)))
          (progn
            (push (attention-layer weights options head-size l cache) layers)
            (setq cache (+ cache 1)))
          (progn
            (push (deltanet-layer weights l slot) layers)
            (setq slot (+ slot 1))))
      (push (swiglu-layer weights l) layers))
    (coerce (nreverse layers) 'vector)))

(defun model-options (head-size options)
  ;; The model-level half of the table: what the forward pass reads once per
  ;; token rather than once per layer. RoPE's frequency table depends on the
  ;; rotary dim, so that one is model-wide -- no published model varies it per
  ;; layer, and one that did would need a table per layer.
  (list :eps (opt options :eps *eps*)
        :rope-theta (opt options :rope-theta 10000.0)
        :rotary-dim (opt options :rotary-dim head-size)
        :emb-mult (opt options :embedding-multiplier 1.0)
        :residual-mult (opt options :residual-multiplier 1.0)
        :logit-mult (opt options :logit-multiplier 1.0)))

;;; --- the checkpoint: config + weights ---------------------------------------
;;; A model is a plist: the sizes, the model-level options, the embedding table,
;;; the final norm, the classifier, and :layers -- the layer list above. The
;;; weights arrive as simple vectors indexed by layer, which is the shape every
;;; reader of a published checkpoint produces too.

(defun load-checkpoint (path)
  ;; llama2.c's .bin: seven int32 header fields, then the weights in one fixed
  ;; order. It is Llama 2 exactly, so the architecture row is `llama` and every
  ;; option keeps its default -- the table degenerates to :attention + :swiglu
  ;; per block. The one thing the FILE decides rather than the family is the RoPE
  ;; layout: run.c rotates adjacent pairs, so :pairs goes in front of the row.
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
           ;; q-dim is n-heads * head-size, which is dim here and need not be
           ;; (Qwen3-0.6B: dim 1024, 16 heads of 128)
           (q-dim (* head-size n-heads))
           (options (cons :rope (cons :pairs (architecture "llama"))))
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
        (let ((wcls (if shared emb (read-f32-matrix s vocab dim)))
              (weights
               (list :rms-att rms-att
                     :wq wq
                     :wk wk
                     :wv wv
                     :wo wo
                     :rms-ffn rms-ffn
                     :w1 w1
                     :w2 w2
                     :w3 w3)))
          (append (model-options head-size options)
                  (list :dim dim
                        :hidden hidden
                        :n-layers n-layers
                        :n-heads n-heads
                        :n-kv-heads n-kv-heads
                        :vocab vocab
                        :seq-len seq-len
                        :head-size head-size
                        :kv-dim kv-dim
                        :q-dim q-dim
                        :emb emb
                        :rms-final rms-final
                        :wcls wcls
                        :layers (transformer-layers n-layers head-size weights
                                                    options))))))))

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

;;; --- the state: KV cache and RoPE tables --------------------------------------

(defun layer-kind-count (layers kind)
  ;; How many layers of KIND the list has: the KV-cache slots (:attention) or
  ;; the recurrent states (:deltanet) it needs.
  (let ((n 0))
    (dotimes (i (length layers) n)
      (if (eq (getf (aref layers i) :kind) kind) (setq n (+ n 1))))))

(defun deltanet-states (layers)
  ;; One deltanet-state per :deltanet layer, indexed by the layer's :slot.
  (let ((states (make-array (layer-kind-count layers :deltanet))))
    (dotimes (i (length layers) states)
      (let ((layer (aref layers i)))
        (when (eq (getf layer :kind) :deltanet)
          (setf (aref states (getf layer :slot)) (deltanet-state layer)))))))

(defun make-state (model)
  ;; The KV cache: per attention layer, per kv-head, keys (seq-len x hs)
  ;; row-major and values (hs x seq-len) transposed -- see the header. Plus the
  ;; RoPE tables, which every layer that rotates at all shares, and the
  ;; recurrent state of every :deltanet layer.
  (let* ((layers (getf model :layers))
         (n-cache (layer-kind-count layers :attention))
         (n-kv (getf model :n-kv-heads))
         (seq-len (getf model :seq-len))
         (hs (getf model :head-size))
         (rot (getf model :rotary-dim))
         (theta (getf model :rope-theta))
         (kc (make-array (list n-cache n-kv)))
         (vt (make-array (list n-cache n-kv)))
         (half (floor rot 2))
         (rope-cos
          (make-array (list seq-len half)
                      :element-type 'single-float
                      :initial-element 0.0))
         (rope-sin
          (make-array (list seq-len half)
                      :element-type 'single-float
                      :initial-element 0.0)))
    (dotimes (l n-cache)
      (dotimes (h n-kv)
        (setf (aref kc l h)
              (linalg:zeros (list seq-len hs) :element-type 'single-float))
        (setf (aref vt l h)
              (linalg:zeros (list hs seq-len) :element-type 'single-float))))
    ;; RoPE: freq_i = 1 / theta^(2i/rotary-dim), angle = pos * freq_i
    (dotimes (pos seq-len)
      (dotimes (i half)
        (let ((angle (* pos (/ 1.0 (expt theta (/ (* 2.0 i) rot))))))
          (setf (aref rope-cos pos i) (cos angle))
          (setf (aref rope-sin pos i) (sin angle)))))
    (list :kc kc
          :vt vt
          :rope-cos rope-cos
          :rope-sin rope-sin
          :att (vec:zeros seq-len :element-type 'single-float)
          :ssm (deltanet-states layers))))

;;; --- the pieces a layer is made of --------------------------------------------

(defun rmsnorm (x g eps)
  ;; x / rms(x) * g, the sum of squares being one vec:dot
  (vec:mul (vec:scale x (/ 1.0 (sqrt (+ (/ (vec:dot x x) (length x)) eps)))) g))

(defun head-rmsnorm (v n-heads hs g eps)
  ;; QK-norm: RMSNorm each head's own hs dims of V in place, with the one weight
  ;; vector G every head shares.
  (dotimes (h n-heads)
    (let ((base (* h hs)) (ss 0.0))
      (dotimes (i hs) (let ((x (aref v (+ base i)))) (setq ss (+ ss (* x x)))))
      (let ((scale (/ 1.0 (sqrt (+ (/ ss hs) eps)))))
        (dotimes (i hs)
          (setf (aref v (+ base i))
                (* (aref v (+ base i)) scale (aref g i))))))))

(defun rope-pairs (v n-heads hs rot pos rope-cos rope-sin)
  ;; Rotate every head's (2i, 2i+1) pairs in place, over its first ROT dims.
  (let ((half (floor rot 2)))
    (dotimes (h n-heads)
      (dotimes (i half)
        (let* ((j (+ (* h hs) (* 2 i)))
               (fcr (aref rope-cos pos i))
               (fci (aref rope-sin pos i))
               (v0 (aref v j))
               (v1 (aref v (+ j 1))))
          (setf (aref v j) (- (* v0 fcr) (* v1 fci)))
          (setf (aref v (+ j 1)) (+ (* v0 fci) (* v1 fcr))))))))

(defun rope-halves (v n-heads hs rot pos rope-cos rope-sin)
  ;; HF's rotate_half: dim i pairs with dim i + rot/2 inside the head.
  (let ((half (floor rot 2)))
    (dotimes (h n-heads)
      (dotimes (i half)
        (let* ((base (* h hs))
               (j (+ base i))
               (j2 (+ base i half))
               (fcr (aref rope-cos pos i))
               (fci (aref rope-sin pos i))
               (v0 (aref v j))
               (v1 (aref v j2)))
          (setf (aref v j) (- (* v0 fcr) (* v1 fci)))
          (setf (aref v j2) (+ (* v0 fci) (* v1 fcr))))))))

(defun apply-rope (style v n-heads hs rot pos rope-cos rope-sin)
  (cond ((eq style :pairs) (rope-pairs v n-heads hs rot pos rope-cos rope-sin))
   ((eq style :halves) (rope-halves v n-heads hs rot pos rope-cos rope-sin))
   (t (error "unknown rope style: ~a" style))))

(defun attention (model state layer q k v pos)
  ;; Multi-head causal attention over the KV cache; returns the concatenated
  ;; head outputs (n-heads x head-size), before the wo projection.
  (let* ((n-heads (getf model :n-heads))
         (n-kv (getf model :n-kv-heads))
         (kv-mul (floor n-heads n-kv))
         (hs (getf model :head-size))
         (l (getf layer :cache))
         (kc (getf state :kc))
         (vt (getf state :vt))
         (att (getf state :att))
         (out (vec:zeros (getf model :q-dim) :element-type 'single-float))
         (qh (vec:zeros hs :element-type 'single-float))
         (inv-sqrt-hs (getf layer :scale)))
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

(defun sigmoid (h)
  ;; 1 / (1 + exp(-x)) over the whole vector: three vec ufuncs instead of one
  ;; boxed funcall per element
  (vec:reciprocal
   (vec:add (vec:ones (length h) :element-type 'single-float)
            (vec:exp (vec:negative h)))))

(defun silu (h)
  ;; x * sigmoid(x)
  (vec:mul h (sigmoid h)))

;;; --- the layer kinds ----------------------------------------------------------
;;; Each takes the NORMED input and returns what the residual adds back.

(defun attention-forward (model state layer xb pos)
  ;; The whole attention mixer: the projections, the options on q and k, causal
  ;; attention over the KV cache, the optional output gate, and wo.
  (let* ((n-heads (getf model :n-heads))
         (n-kv (getf model :n-kv-heads))
         (hs (getf model :head-size))
         (eps (getf model :eps))
         (rot (getf layer :rotary-dim))
         (style (getf layer :rope))
         (q-norm (getf layer :q-norm))
         (k-norm (getf layer :k-norm))
         (gate (getf layer :gate))
         (q (vec:matvec (getf layer :wq) xb))
         (k (vec:matvec (getf layer :wk) xb))
         (v (vec:matvec (getf layer :wv) xb)))
    (when q-norm (head-rmsnorm q n-heads hs q-norm eps))
    (when k-norm (head-rmsnorm k n-kv hs k-norm eps))
    (when style
      (let ((rope-cos (getf state :rope-cos)) (rope-sin (getf state :rope-sin)))
        (apply-rope style q n-heads hs rot pos rope-cos rope-sin)
        (apply-rope style k n-kv hs rot pos rope-cos rope-sin)))
    (let ((out (attention model state layer q k v pos)))
      (when gate (setq out (vec:mul out (sigmoid (vec:matvec gate xb)))))
      (vec:matvec (getf layer :wo) out))))

(defun swiglu-forward (layer xb)
  ;; w2 (silu(w1 x) * w3 x)
  (let ((h1 (vec:matvec (getf layer :w1) xb))
        (h3 (vec:matvec (getf layer :w3) xb)))
    (vec:matvec (getf layer :w2) (vec:mul (silu h1) h3))))

(defun layer-forward (model state layer x pos)
  ;; One layer, whatever kind it is: normalise, mix, add back.
  (let* ((kind (getf layer :kind))
         (xb (rmsnorm x (getf layer :norm) (getf model :eps)))
         (out
          (cond
           ((eq kind :attention) (attention-forward model state layer xb pos))
           ((eq kind :swiglu) (swiglu-forward layer xb))
           ((eq kind :deltanet)
            (deltanet-forward layer (aref (getf state :ssm) (getf layer :slot))
                              xb (getf model :eps)))
           (t (error "unknown layer kind: ~a" kind))))
         (mult (getf model :residual-mult)))
    (vec:add x (if (= mult 1.0) out (vec:scale out mult)))))

(defun forward (model state token pos)
  ;; -> the logits over the vocabulary
  (let ((x (linalg:row (getf model :emb) token))
        (layers (getf model :layers))
        (emb-mult (getf model :emb-mult))
        (logit-mult (getf model :logit-mult)))
    (unless (= emb-mult 1.0) (setq x (vec:scale x emb-mult)))
    (dotimes (i (length layers))
      (setq x (layer-forward model state (aref layers i) x pos)))
    (let ((logits
           (vec:matvec (getf model :wcls)
                       (rmsnorm x (getf model :rms-final) (getf model :eps)))))
      (if (= logit-mult 1.0) logits (vec:scale logits logit-mult)))))

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
