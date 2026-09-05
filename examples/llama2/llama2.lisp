;;;; llama2.c in rontolisp: run a Llama 2 model from a llama2.c checkpoint.
;;;;
;;;; This is Andrej Karpathy's run.c (https://github.com/karpathy/llama2.c)
;;;; ported whole: the checkpoint loader, the tokenizer (the shipped tokenizer:
;;;; package over tokenizer.bin's pieces and scores), the transformer forward
;;;; pass, the temperature / top-p sampler and the generate loop. Feed it a
;;;; checkpoint the C program reads and it tells the same stories -- token for
;;;; token, at temperature 0 and at any seed (the sampler is run.c's xorshift,
;;;; bit for bit). ml/tiny-llm.lisp is the arithmetic core of this file with the
;;;; I/O taken away; this is the whole engine. Feed it the DIRECTORY a Hugging
;;;; Face model page downloads to (config.json, model.safetensors,
;;;; tokenizer.json) and it runs that model instead: TinyLlama, Qwen3.5,
;;;; LFM2.5 -- the README has each.
;;;;
;;;; THE LAYER TABLE
;;;; ---------------
;;;; What is not run.c: the forward pass is a TABLE OF LAYER KINDS rather than
;;;; Llama 2 spelled out. A model is a list of layers, every one of them the same
;;;; residual sandwich -- normalise, mix, add back -- differing only in its kind
;;;; (:attention, :swiglu, :deltanet -- the Gated DeltaNet recurrence of
;;;; Qwen3.5, deltanet.lisp beside this file -- or :shortconv, LFM2's gated
;;;; short convolution, shortconv.lisp) and in the options beside it:
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
;; run.c's -m: "generate" continues the prompt; "chat" wraps it in the model's
;; chat template (the families that have one, with thinking off)
(defparameter *mode* (flag-or-env "-m" "LLAMA2_MODE" "generate"))
;; LLAMA2_TRACE=1: every token id and its text on stderr as it is produced
(defparameter *trace* (env-or "LLAMA2_TRACE" nil))

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
;;; and differs only in what f is -- :attention, :swiglu, :deltanet (the gated
;;; linear recurrence Qwen3.5 puts in three of every four blocks, deltanet.lisp)
;;; or :shortconv (the gated convolution LFM2 puts in ten of sixteen,
;;; shortconv.lisp) -- and in the options beside it. Llama 2 is :attention then :swiglu per block with every option at
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
;;;   :mixer              a hybrid's other token mixer, :deltanet or :shortconv
;;;   :full-attention-interval
;;;                       every Nth block (1-based) is :attention, the others
;;;                       the :mixer (Qwen3.5: 4)
;;;   :layer-types        the explicit list instead, one of :attention /
;;;                       :deltanet / :shortconv per block -- what a reader
;;;                       builds from config.json's layer_types
;;;                       ("full_attention" / "linear_attention" / "conv") or,
;;;                       in a GGUF without an interval, from the per-layer
;;;                       head-count array (LFM2: 0 heads = :shortconv). It is
;;;                       per checkpoint, so a reader passes it, and it wins
;;;                       over the interval
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
;;;   :tokenizer          the byte-level BPE pre-tokenizer of the family's
;;;                       tokenizer.json (tokenizer:pre-tokenize's kinds); a
;;;                       family without one (Llama 2) reads a tokenizer.bin
;;;   :chat               the chat template as a format control, for -m chat
(defparameter *chatml*
  "<|im_start|>user~%~a<|im_end|>~%<|im_start|>assistant~%")

(defparameter *chatml-think-off*
  ;; Qwen3 / 3.5: an empty think block is how the template turns thinking off
  "<|im_start|>user~%~a<|im_end|>~%<|im_start|>assistant~%<think>~%~%</think>~%~%")

(defparameter *architectures*
  (list (list "llama") ; Llama 2, TinyLlama, and karpathy's stories*.bin
        (list "qwen3"
              :qk-norm t
              :rope-theta 1000000.0
              :eps 0.000001
              :tied t
              :tokenizer :qwen2
              :chat *chatml-think-off*)
        ;; Qwen3.5 / 3.6 / 3.8 dense: 3 of 4 blocks Gated DeltaNet, the 4th
        ;; gated attention (head_dim 256, GQA) with QK-norm and partial RoPE.
        ;; Its config's vocab_size (248320) is the PADDED embedding table; the
        ;; tokenizer defines 248070 ids, and the sampler chooses among those
        (list "qwen35"
              :mixer :deltanet
              :full-attention-interval 4
              :qk-norm t
              :rotary-dim 64
              :rope-theta 10000000.0
              :eps 0.000001
              :tied t
              :tokenizer :qwen35
              :chat *chatml-think-off*)
        (list "smollm3" :no-rope-interval 4 :rope-theta 5000000.0 :tied t)
        (list "granite" :rope-theta 10000000.0)
        ;; LFM2 / LFM2.5: ten of sixteen blocks a gated short convolution, the
        ;; rest attention with QK-norm (GQA 32/8, head_dim 64); the readers
        ;; always pass :layer-types, the pattern has no interval
        (list "lfm2"
              :mixer :shortconv
              :qk-norm t
              :rope-theta 1000000.0
              :eps 0.00001
              :tied t
              :tokenizer :llama3
              :chat *chatml*)))

(defun architecture (name)
  ;; The row for NAME, or an error naming the architectures there are.
  (do ((rows *architectures* (cdr rows)))
      ((null rows) (error "unsupported architecture: ~a" name))
    (if (string= (car (car rows)) name) (return (cdr (car rows))))))

;;; --- building the layer list ------------------------------------------------

;; the hybrid kinds: <kind>-layer, <kind>-state, <kind>-forward
(load "deltanet.lisp")
(load "shortconv.lisp")

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

(defun mixer-kind (options l)
  ;; Which token mixer block L has: the reader's :layer-types entry, else the
  ;; :full-attention-interval rule with the row's :mixer, else :attention.
  (let ((types (opt options :layer-types nil))
        (interval (opt options :full-attention-interval nil)))
    (cond (types (nth l types))
          ((and interval (/= 0 (mod (+ l 1) interval)))
           (opt options :mixer :attention))
          (t :attention))))

(defun transformer-layers (n-layers head-size weights options)
  ;; The layer list of a Llama-2-shaped model: per block a token mixer and a
  ;; feed-forward. WEIGHTS is the plist of per-layer simple vectors every loader
  ;; builds -- :rms-att :wq :wk :wv :wo (:q-norm :k-norm :attn-gate optional)
  ;; :rms-ffn :w1 :w2 :w3, plus the :ssm-* vectors deltanet.lisp lists and the
  ;; :conv-* vectors shortconv.lisp lists for the blocks a hybrid gives those
  ;; kinds (the entries of the other kinds' vectors are nil there) -- and
  ;; OPTIONS an architecture row with the reader's own keys in front of it.
  ;; The non-attention mixers take the recurrent-state slots in order.
  (let ((layers '()) (cache 0) (slot 0))
    (dotimes (l n-layers)
      (let ((kind (mixer-kind options l)))
        (cond ((eq kind :attention)
               (push (attention-layer weights options head-size l cache) layers)
               (setq cache (+ cache 1)))
              ((eq kind :deltanet)
               (push (deltanet-layer weights l slot) layers)
               (setq slot (+ slot 1)))
              ((eq kind :shortconv)
               (push (shortconv-layer weights l slot) layers)
               (setq slot (+ slot 1)))
              (t (error "unknown layer type: ~a" kind))))
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
        :logit-mult (opt options :logit-multiplier 1.0)
        :tokenizer (opt options :tokenizer nil)
        :chat (opt options :chat nil)))

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

;;; --- a Hugging Face checkpoint: config.json + safetensors ---------------------
;;; The directory a model page downloads to: config.json for the sizes and the
;;; family, model.safetensors (or the sharded index) for the weights, read by
;;; the shipped safetensors: package into the same plist the .bin loader
;;; builds. What differs per family is the tensor NAMES and a few stored forms
;;; (Qwen3.5's norms as offsets from 1, its A_log, its query|gate interleave),
;;; all resolved here so the table sees the shapes it expects. HF's layout is
;;; the un-permuted one, so the RoPE layout is :halves for every family.

(defparameter *seq-len-cap* 4096)

(defun read-text-file (path)
  ;; The whole UTF-8 text of a file, read as bytes in 1 MB chunks (file-length
  ;; is nil on WASM) and decoded once -- tokenizer.json is 13 MB, and a
  ;; line-by-line concatenation of it is quadratic.
  (with-open-file (s path :element-type '(unsigned-byte 8))
    (let ((chunks '())
          (total 0)
          (buf (make-array 1048576 :element-type '(unsigned-byte 8))))
      (loop
        (let ((n (read-sequence buf s)))
          (when (= n 0) (return))
          (push (subseq buf 0 n) chunks)
          (setq total (+ total n))
          (when (< n 1048576) (return))))
      (let ((bytes (make-array total :element-type '(unsigned-byte 8))) (at 0))
        (dolist (chunk (nreverse chunks))
          (replace bytes chunk :start1 at)
          (setq at (+ at (length chunk))))
        (rontolisp:octets-to-string bytes)))))

(defun hf-config (dir)
  ;; config.json, with a multimodal checkpoint's text_config unwrapped (its
  ;; model_type is the text model's) -- and the tie_word_embeddings /
  ;; tie_embedding spellings folded into one.
  (let* ((outer
          (rontolisp:json-parse
           (read-text-file (concatenate 'string dir "config.json"))))
         (config (or (gethash "text_config" outer) outer)))
    (unless (gethash "tie_word_embeddings" config)
      (setf (gethash "tie_word_embeddings" config)
            (or (gethash "tie_embedding" outer)
                (gethash "tie_word_embeddings" outer))))
    config))

(defun hf-architecture (model-type)
  ;; config.json's model_type -> the *architectures* row name.
  (cond ((string= model-type "llama") "llama")
        ((string= model-type "qwen3") "qwen3")
        ((or (string= model-type "qwen3_5") (string= model-type "qwen3_5_text"))
         "qwen35")
        ((string= model-type "smollm3") "smollm3")
        ((string= model-type "granite") "granite")
        ((string= model-type "lfm2") "lfm2")
        (t (error "unsupported model_type: ~a" model-type))))

(defun starts-with (string prefix)
  (and (>= (length string) (length prefix))
       (string= string prefix :end1 (length prefix))))

(defun plus-one (v)
  ;; Qwen3.5 stores a norm weight as an offset from 1 (x * (1 + w)).
  (vec:add v (vec:ones (length v) :element-type 'single-float)))

(defun squeeze-middle (a)
  ;; A [c, 1, k] conv1d weight as the c x k matrix the conv step takes.
  (let* ((dims (array-dimensions a))
         (c (first dims))
         (k (third dims))
         (m
          (make-array (list c k)
                      :element-type 'single-float
                      :initial-element 0.0)))
    (dotimes (i c m) (dotimes (j k) (setf (aref m i j) (aref a i 0 j))))))

(defun split-gated-q (w n-heads hs)
  ;; A [heads x (query | gate), dim] q_proj -> (values wq gate), each
  ;; [heads x hs, dim]: row h*2hs + i is head h's query row i, row h*2hs + hs + i
  ;; its gate row i.
  (let* ((dim (array-dimension w 1))
         (rows (* n-heads hs))
         (wq
          (make-array (list rows dim)
                      :element-type 'single-float
                      :initial-element 0.0))
         (gate
          (make-array (list rows dim)
                      :element-type 'single-float
                      :initial-element 0.0)))
    (dotimes (h n-heads)
      (dotimes (i hs)
        (let ((src (+ (* h 2 hs) i)) (dst (+ (* h hs) i)))
          (dotimes (j dim)
            (setf (aref wq dst j) (aref w src j))
            (setf (aref gate dst j) (aref w (+ src hs) j))))))
    (values wq gate)))

(defun layer-types (config)
  ;; config.json's layer_types -> the table's keywords, or nil when absent.
  (let ((types (gethash "layer_types" config)))
    (if types
        (map 'list
             (lambda (name)
               (cond ((string= name "full_attention") :attention)
                     ((string= name "linear_attention") :deltanet)
                     ((string= name "conv") :shortconv)
                     (t (error "unknown layer type: ~a" name)))) types)
        nil)))

(defun load-hf-checkpoint (dir)
  (let* ((dir
          (if (char= (char dir (- (length dir) 1)) #\/)
              dir
              (concatenate 'string dir "/")))
         (config (hf-config dir))
         (family (hf-architecture (gethash "model_type" config)))
         (qwen35 (string= family "qwen35"))
         (lfm2 (string= family "lfm2"))
         (dim (gethash "hidden_size" config))
         (n-layers (gethash "num_hidden_layers" config))
         (n-heads (gethash "num_attention_heads" config))
         (n-kv-heads (or (gethash "num_key_value_heads" config) n-heads))
         (head-size (or (gethash "head_dim" config) (floor dim n-heads)))
         (vocab (gethash "vocab_size" config))
         (tied (gethash "tie_word_embeddings" config))
         (rope-params (gethash "rope_parameters" config))
         (rope-theta
          (or (gethash "rope_theta" config)
              (and rope-params (gethash "rope_theta" rope-params)) 10000.0))
         (partial
          (and rope-params (gethash "partial_rotary_factor" rope-params)))
         (eps
          (or (gethash "rms_norm_eps" config) (gethash "norm_eps" config)
              *eps*))
         (seq-len
          (min *seq-len-cap*
               (or (gethash "max_position_embeddings" config) *seq-len-cap*)))
         (types (layer-types config))
         ;; the language model's prefix: a multimodal checkpoint keeps it under
         ;; model.language_model. beside model.visual. and the mtp.* head
         (prefix (if qwen35 "model.language_model." "model."))
         (tensors
          (safetensors:read dir
                            :only (lambda (name)
                                    (or (starts-with name prefix)
                                        (string= name "lm_head.weight")
                                        (and (not qwen35)
                                             (starts-with name "model."))))))
         (n-att (if lfm2 "operator_norm" "input_layernorm"))
         (n-ffn (if lfm2 "ffn_norm" "post_attention_layernorm"))
         (per-layer
          (lambda (f)
            (let ((v (make-array n-layers)))
              (dotimes (l n-layers v) (setf (aref v l) (funcall f l)))))))
    (labels ((tensor (name) (gethash name tensors))
             (layer-tensor (l suffix)
               (tensor (format nil "~alayers.~a.~a" prefix l suffix)))
             (norm-tensor (l suffix)
               (let ((v (layer-tensor l suffix)))
                 (if (and v qwen35) (plus-one v) v)))
             (attention-p (l) (if types (eq (nth l types) :attention) t))
             (att (l suffix) (and (attention-p l) (layer-tensor l suffix))))
      (let* ((emb (tensor (concatenate 'string prefix "embed_tokens.weight")))
             (wcls (if tied emb (tensor "lm_head.weight")))
             (rms-final
              (let ((v
                     (tensor
                      (concatenate 'string prefix
                       (if lfm2 "embedding_norm.weight" "norm.weight")))))
                (if qwen35 (plus-one v) v)))
             (wq (make-array n-layers))
             (gate (make-array n-layers)))
        ;; the attention projections, with Qwen3.5's query | gate split
        (dotimes (l n-layers)
          (let ((w (att l "self_attn.q_proj.weight")))
            (cond ((null w))
                  ((gethash "attn_output_gate" config)
                   (multiple-value-bind (q g)
                       (split-gated-q w n-heads head-size)
                     (setf (aref wq l) q)
                     (setf (aref gate l) g)))
                  (t (setf (aref wq l) w)))))
        (let* ((weights
                (list :rms-att (funcall per-layer
                                        (lambda (l)
                                          (norm-tensor l
                                                       (concatenate 'string
                                                        n-att ".weight"))))
                      :rms-ffn (funcall per-layer
                                        (lambda (l)
                                          (norm-tensor l
                                                       (concatenate 'string
                                                        n-ffn ".weight"))))
                      :wq wq
                      :attn-gate gate
                      :wk (funcall per-layer
                           (lambda (l) (att l "self_attn.k_proj.weight")))
                      :wv (funcall per-layer
                           (lambda (l) (att l "self_attn.v_proj.weight")))
                      :wo (funcall per-layer
                                   (lambda (l)
                                     (att l
                                          (if lfm2
                                              "self_attn.out_proj.weight"
                                              "self_attn.o_proj.weight"))))
                      :q-norm (funcall per-layer
                                       (lambda (l)
                                         (let ((v
                                                (att l
                                                 (if lfm2
                                                     "self_attn.q_layernorm.weight"
                                                     "self_attn.q_norm.weight"))))
                                           (if (and v qwen35) (plus-one v) v))))
                      :k-norm (funcall per-layer
                                       (lambda (l)
                                         (let ((v
                                                (att l
                                                 (if lfm2
                                                     "self_attn.k_layernorm.weight"
                                                     "self_attn.k_norm.weight"))))
                                           (if (and v qwen35) (plus-one v) v))))
                      :w1 (funcall per-layer
                                   (lambda (l)
                                     (layer-tensor l
                                                   (if lfm2
                                                       "feed_forward.w1.weight"
                                                       "mlp.gate_proj.weight"))))
                      :w3 (funcall per-layer
                                   (lambda (l)
                                     (layer-tensor l
                                                   (if lfm2
                                                       "feed_forward.w3.weight"
                                                       "mlp.up_proj.weight"))))
                      :w2 (funcall per-layer
                                   (lambda (l)
                                     (layer-tensor l
                                                   (if lfm2
                                                       "feed_forward.w2.weight"
                                                       "mlp.down_proj.weight"))))
                      ;; the Gated DeltaNet blocks (Qwen3.5)
                      :ssm-qkv (funcall per-layer
                                        (lambda (l)
                                          (layer-tensor l
                                           "linear_attn.in_proj_qkv.weight")))
                      :ssm-z (funcall per-layer
                                      (lambda (l)
                                        (layer-tensor l
                                         "linear_attn.in_proj_z.weight")))
                      :ssm-beta (funcall per-layer
                                         (lambda (l)
                                           (layer-tensor l
                                            "linear_attn.in_proj_b.weight")))
                      :ssm-alpha (funcall per-layer
                                          (lambda (l)
                                            (layer-tensor l
                                             "linear_attn.in_proj_a.weight")))
                      :ssm-conv (funcall per-layer
                                         (lambda (l)
                                           (let ((w
                                                  (layer-tensor l
                                                                "linear_attn.conv1d.weight")))
                                             (and w (squeeze-middle w)))))
                      :ssm-a (funcall per-layer
                                      (lambda (l)
                                        (let ((a
                                               (layer-tensor l
                                                "linear_attn.A_log")))
                                          (and a (vec:negative (vec:exp a))))))
                      :ssm-dt-bias (funcall per-layer
                                            (lambda (l)
                                              (layer-tensor l
                                               "linear_attn.dt_bias")))
                      :ssm-norm (funcall per-layer
                                         (lambda (l)
                                           (layer-tensor l
                                            "linear_attn.norm.weight")))
                      :ssm-out (funcall per-layer
                                        (lambda (l)
                                          (layer-tensor l
                                           "linear_attn.out_proj.weight")))
                      ;; the short-conv blocks (LFM2)
                      :conv-in (funcall per-layer
                                        (lambda (l)
                                          (layer-tensor l
                                                        "conv.in_proj.weight")))
                      :conv-w (funcall per-layer
                                       (lambda (l)
                                         (let ((w
                                                (layer-tensor l
                                                 "conv.conv.weight")))
                                           (and w (squeeze-middle w)))))
                      :conv-out (funcall per-layer
                                         (lambda (l)
                                           (layer-tensor l
                                            "conv.out_proj.weight")))))
               (options
                (append (list :rope :halves :eps eps :rope-theta rope-theta)
                        (if partial
                            (list :rotary-dim (floor (* head-size partial)))
                            nil) (if types (list :layer-types types) nil)
                        (architecture family)))
               (hidden (array-dimension (aref (getf weights :w1) 0) 0)))
          (append (model-options head-size options)
                  (list :dim dim
                        :hidden hidden
                        :n-layers n-layers
                        :n-heads n-heads
                        :n-kv-heads n-kv-heads
                        :vocab vocab
                        :seq-len seq-len
                        :head-size head-size
                        :kv-dim (* head-size n-kv-heads)
                        :q-dim (* head-size n-heads)
                        :eos (gethash "eos_token_id" config)
                        :emb emb
                        :rms-final rms-final
                        :wcls wcls
                        :layers (transformer-layers n-layers head-size weights
                                                    options))))))))

;;; --- a GGUF checkpoint ---------------------------------------------------------------
;;; The one file a downloaded model most often is: the hyperparameters and the
;;; tokenizer in its key/value block, the weights behind them, read by the
;;; shipped gguf: package. The names are llama.cpp's; what its converter
;;; already did to the weights -- Qwen3.5's norms stored as 1 + w, A_log as
;;; -exp(A_log), the conv squeezed -- is left as it is, and what it did NOT do
;;; (split the query | gate interleave) is done here. The converter permutes Q
;;; and K only for the families whose rope type is "normal" (llama, smollm3,
;;; granite), so those are :pairs and the Qwen / LFM2 families :halves.

(defun gguf-architecture (name)
  ;; general.architecture -> the *architectures* row name (they agree, except
  ;; that a row may be missing).
  (if (assoc name *architectures* :test #'string=)
      name
      (error "unsupported architecture: ~a" name)))

(defun gguf-rope-layout (name)
  (if (or (string= name "llama") (string= name "smollm3")
          (string= name "granite"))
      :pairs :halves))

(defun gguf-layer-types (m arch n-layers)
  ;; The explicit block kinds a GGUF encodes: LFM2 marks a conv block with 0 KV
  ;; heads in its per-layer head_count_kv array; a hybrid with an interval
  ;; (Qwen3.5) needs no list. nil when the file says nothing.
  (let ((kv
         (gguf:metadata-value m
          (concatenate 'string arch ".attention.head_count_kv") nil)))
    (if (and kv (not (integerp kv)))
        (let ((types '()))
          (dotimes (l n-layers (nreverse types))
            (push (if (= (aref kv l) 0) :shortconv :attention) types)))
        nil)))

(defun load-gguf-checkpoint (path)
  (let* ((meta (gguf:read path :metadata-only t))
         (arch
          (gguf-architecture (gguf:metadata-value meta "general.architecture")))
         (key
          (lambda (suffix &optional default)
            (gguf:metadata-value meta (concatenate 'string arch "." suffix)
                                 default)))
         (qwen35 (string= arch "qwen35"))
         (lfm2 (string= arch "lfm2"))
         (dim (funcall key "embedding_length"))
         ;; Qwen3.5's block_count counts its speculative (MTP) block too, which
         ;; is not part of the language model and is skipped
         (n-layers
          (- (funcall key "block_count")
             (funcall key "nextn_predict_layers" 0)))
         (heads-value (funcall key "attention.head_count"))
         (n-heads
          (if (integerp heads-value)
              heads-value
              (reduce #'max (coerce heads-value 'list))))
         (kv-value (funcall key "attention.head_count_kv" n-heads))
         (n-kv-heads
          (if (integerp kv-value)
              kv-value
              (reduce #'max (coerce kv-value 'list))))
         (head-size (funcall key "attention.key_length" (floor dim n-heads)))
         (eps (funcall key "attention.layer_norm_rms_epsilon" *eps*))
         (rope-theta (funcall key "rope.freq_base" 10000.0))
         (rotary (funcall key "rope.dimension_count" nil))
         (interval (funcall key "full_attention_interval" nil))
         (seq-len
          (min *seq-len-cap* (funcall key "context_length" *seq-len-cap*)))
         (types (gguf-layer-types meta arch n-layers))
         (row (architecture arch))
         (options
          (append
           (list :rope (gguf-rope-layout arch) :eps eps :rope-theta rope-theta)
           (if rotary (list :rotary-dim rotary) nil)
           (if interval (list :full-attention-interval interval) nil)
           (if types (list :layer-types types) nil) row))
         ;; every tensor of the language model, so the walk stages nothing else
         ;; (the MTP block, past n-layers, is passed over)
         (wanted
          (let ((names '()))
            (dolist (name (gguf:tensor-names meta) (nreverse names))
              (let ((blk
                     (and (starts-with name "blk.")
                          (parse-integer name :start 4 :junk-allowed t))))
                (when (or (null blk) (< blk n-layers)) (push name names))))))
         (file (gguf:read path :only wanted))
         (per-layer
          (lambda (f)
            (let ((v (make-array n-layers)))
              (dotimes (l n-layers v) (setf (aref v l) (funcall f l)))))))
    (labels ((tensor (name) (gguf:tensor file name))
             (layer-tensor (l suffix)
               (tensor (format nil "blk.~a.~a" l suffix)))
             (required (l suffix)
               ;; a tensor every block must have: a missing one is a naming
               ;; error, reported by name rather than as a nil downstream
               (or (layer-tensor l suffix)
                   (error "gguf: blk.~a.~a is missing from ~a" l suffix path))))
      (let* ((emb (tensor "token_embd.weight"))
             (wcls (or (tensor "output.weight") emb))
             (rms-final
              (or (tensor "output_norm.weight")
                  (tensor "token_embd_norm.weight")))
             (wq (make-array n-layers))
             (gate (make-array n-layers)))
        ;; the attention projections; Qwen3.5's attn_q keeps HF's query | gate
        ;; interleave, told by its row count
        (dotimes (l n-layers)
          (let ((w (layer-tensor l "attn_q.weight")))
            (cond ((null w))
                  ((= (array-dimension w 0) (* 2 n-heads head-size))
                   (multiple-value-bind (q g)
                       (split-gated-q w n-heads head-size)
                     (setf (aref wq l) q)
                     (setf (aref gate l) g)))
                  (t (setf (aref wq l) w)))))
        (let* ((weights
                (list :rms-att (funcall per-layer
                                (lambda (l) (required l "attn_norm.weight")))
                      ;; the feed-forward norm is ffn_norm in most families and
                      ;; post_attention_norm in Qwen3.5's
                      :rms-ffn (funcall per-layer
                                        (lambda (l)
                                          (or (layer-tensor l "ffn_norm.weight")
                                              (required l
                                               "post_attention_norm.weight"))))
                      :wq wq
                      :attn-gate gate
                      :wk (funcall per-layer
                           (lambda (l) (layer-tensor l "attn_k.weight")))
                      :wv (funcall per-layer
                           (lambda (l) (layer-tensor l "attn_v.weight")))
                      :wo (funcall per-layer
                           (lambda (l) (layer-tensor l "attn_output.weight")))
                      :q-norm (funcall per-layer
                                       (lambda (l)
                                         (layer-tensor l "attn_q_norm.weight")))
                      :k-norm (funcall per-layer
                                       (lambda (l)
                                         (layer-tensor l "attn_k_norm.weight")))
                      :w1 (funcall per-layer
                                   (lambda (l) (required l "ffn_gate.weight")))
                      :w3 (funcall per-layer
                                   (lambda (l) (required l "ffn_up.weight")))
                      :w2 (funcall per-layer
                                   (lambda (l) (required l "ffn_down.weight")))
                      ;; the Gated DeltaNet blocks (Qwen3.5): ssm_a is -exp(A_log)
                      ;; already, ssm_conv1d already conv_dim x kernel
                      :ssm-qkv (funcall per-layer
                                (lambda (l) (layer-tensor l "attn_qkv.weight")))
                      :ssm-z (funcall per-layer
                              (lambda (l) (layer-tensor l "attn_gate.weight")))
                      :ssm-beta (funcall per-layer
                                         (lambda (l)
                                           (layer-tensor l "ssm_beta.weight")))
                      :ssm-alpha (funcall per-layer
                                          (lambda (l)
                                            (layer-tensor l
                                                          "ssm_alpha.weight")))
                      :ssm-conv (funcall per-layer
                                         (lambda (l)
                                           (layer-tensor l
                                                         "ssm_conv1d.weight")))
                      :ssm-a (funcall per-layer
                                      (lambda (l) (layer-tensor l "ssm_a")))
                      :ssm-dt-bias (funcall per-layer
                                    (lambda (l) (layer-tensor l "ssm_dt.bias")))
                      :ssm-norm (funcall per-layer
                                         (lambda (l)
                                           (layer-tensor l "ssm_norm.weight")))
                      :ssm-out (funcall per-layer
                                (lambda (l) (layer-tensor l "ssm_out.weight")))
                      ;; the short-conv blocks (LFM2)
                      :conv-in (funcall per-layer
                                        (lambda (l)
                                          (layer-tensor l
                                           "shortconv.in_proj.weight")))
                      :conv-w (funcall per-layer
                                       (lambda (l)
                                         (layer-tensor l
                                          "shortconv.conv.weight")))
                      :conv-out (funcall per-layer
                                         (lambda (l)
                                           (layer-tensor l
                                            "shortconv.out_proj.weight")))))
               (hidden (array-dimension (aref (getf weights :w1) 0) 0))
               (fields (gguf:tokenizer-fields meta)))
          (append (model-options head-size options)
                  (list :dim dim
                        :hidden hidden
                        :n-layers n-layers
                        :n-heads n-heads
                        :n-kv-heads n-kv-heads
                        :vocab (array-dimension emb 0)
                        :seq-len seq-len
                        :head-size head-size
                        :kv-dim (* head-size n-kv-heads)
                        :q-dim (* head-size n-heads)
                        :eos (getf fields :eos)
                        :emb emb
                        :rms-final rms-final
                        :wcls wcls
                        :gguf-tokenizer fields
                        :add-bos (gguf:metadata-value meta
                                  "tokenizer.ggml.add_bos_token" t)
                        :layers (transformer-layers n-layers head-size weights
                                                    options))))))))

(defun load-gguf-tokenizer (model)
  ;; The tokenizer the GGUF carries: byte-level BPE (model "gpt2") or
  ;; SentencePiece ("llama"), each in the shape the tokenizer package takes.
  ;; A BOS the file says not to add (Qwen) is left out of the tokenizer.
  (let* ((f (getf model :gguf-tokenizer))
         (bos (and (getf model :add-bos) (getf f :bos))))
    (if (string= (getf f :model) "llama")
        (tokenizer:make-sentencepiece (getf f :tokens) (getf f :scores)
                                      :bos bos
                                      :eos (getf f :eos))
        (let ((specials '())
              (tokens (getf f :tokens))
              (types (getf f :token-type)))
          ;; token type 3 = control: the special tokens matched whole
          (when types
            (dotimes (i (length types))
              (when (= (aref types i) 3) (push (aref tokens i) specials))))
          (tokenizer:make-bpe tokens (getf f :merges)
                              :kind (getf f :pre)
                              :specials specials
                              :bos bos
                              :eos (getf f :eos))))))

(defun checkpoint-directory (path)
  ;; The directory of a Hugging Face checkpoint, with its trailing slash; nil
  ;; for llama2.c's .bin.
  (cond ((ends-with-p path ".bin") nil)
        ((ends-with-p path ".gguf") nil)
        ((char= (char path (- (length path) 1)) #\/) path)
        (t (concatenate 'string path "/"))))

(defun ends-with-p (string suffix)
  (and (>= (length string) (length suffix))
       (string= string suffix :start1 (- (length string) (length suffix)))))

(defun load-model (path)
  ;; llama2.c's .bin, a GGUF, or a Hugging Face checkpoint directory.
  (cond ((ends-with-p path ".bin") (load-checkpoint path))
        ((ends-with-p path ".gguf") (load-gguf-checkpoint path))
        (t (load-hf-checkpoint (checkpoint-directory path)))))

;;; --- the tokenizer ------------------------------------------------------------
;;; Both tokenizers are the shipped tokenizer: package; this file only reads
;;; the vocabulary in. llama2.c's tokenizer.bin -- int32 max-token-length, then
;;; per token float32 score, int32 length, UTF-8 bytes -- is the SentencePiece
;;; shape (pieces with scores, the dummy prefix space, byte-fallback pieces);
;;; a Hugging Face checkpoint's tokenizer.json is the byte-level BPE shape
;;; (vocab + ranked merges + added tokens), whose pre-tokenizer the file
;;; itself describes -- SmolLM2 and TinyLlama are both model_type llama, and
;;; only the file tells a byte-level BPE from a SentencePiece one.

(defun load-tokenizer (path vocab)
  ;; tokenizer.bin -> a SentencePiece tokenizer with llama2.c's BOS 1 / EOS 2.
  ;; The scores and lengths are read through one-element packed vectors:
  ;; `read-sequence` over a packed buffer reads raw little-endian elements, so
  ;; this is the checkpoint loader's idiom in small.
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
            (make-array vocab
                        :element-type 'single-float
                        :initial-element 0.0)))
      (dotimes (i vocab)
        (read-sequence f32 s)
        (setf (aref scores i) (aref f32 0))
        (read-sequence u32 s)
        (let ((len (aref u32 0)))
          (read-sequence buf s :end len)
          (setf (aref pieces i)
                (rontolisp:octets-to-string (subseq buf 0 len)))))
      (tokenizer:make-sentencepiece pieces scores :bos 1 :eos 2))))

;;; --- JSON off a byte vector -------------------------------------------------------
;;; tokenizer.json is 13 MB of non-Latin-1 text, and a character index into
;;; such a string re-proves it surrogate-free from the start on every access
;;; when small strings are cut out between accesses (.todo/690), so
;;; rontolisp:json-parse over it does not finish. This reader walks the BYTES
;;; with typed loops and decodes one token string at a time. The values it
;;; builds are json-parse's: objects as string-keyed hash tables, arrays as
;;; vectors, true/false/null as t / nil / the symbol null.

(defun read-file-bytes (path)
  ;; The whole file as an (unsigned-byte 8) vector, read in 1 MB chunks
  ;; (file-length is nil on WASM).
  (with-open-file (s path :element-type '(unsigned-byte 8))
    (let ((chunks '())
          (total 0)
          (buf (make-array 1048576 :element-type '(unsigned-byte 8))))
      (loop
        (let ((n (read-sequence buf s)))
          (when (= n 0) (return))
          (push (subseq buf 0 n) chunks)
          (setq total (+ total n))
          (when (< n 1048576) (return))))
      (let ((bytes (make-array total :element-type '(unsigned-byte 8))) (at 0))
        (dolist (chunk (nreverse chunks) bytes)
          (replace bytes chunk :start1 at)
          (setq at (+ at (length chunk))))))))

(defvar *json-bytes* nil)

(defvar *json-pos* 0)

(defun json-skip-ws ()
  (let ((b *json-bytes*) (i *json-pos*) (n (length *json-bytes*)))
    (loop while
            (and (< i n)
                 (let ((c (aref b i))) (or (= c 32) (= c 10) (= c 13) (= c 9))))
          do (setq i (+ i 1)))
    (setq *json-pos* i)))

(defun json-expect (byte)
  (json-skip-ws)
  (unless (= (aref *json-bytes* *json-pos*) byte)
    (error "json: expected ~a at byte ~a" (code-char byte) *json-pos*))
  (setq *json-pos* (+ *json-pos* 1)))

(defun json-hex (i)
  ;; The four hex digits at I of the byte vector.
  (let ((v 0))
    (dotimes (k 4 v)
      (let* ((c (aref *json-bytes* (+ i k)))
             (d
              (cond ((and (>= c 48) (<= c 57)) (- c 48))
                    ((and (>= c 97) (<= c 102)) (- c 87))
                    ((and (>= c 65) (<= c 70)) (- c 55))
                    (t (error "json: bad hex digit at byte ~a" (+ i k))))))
        (setq v (+ (* v 16) d))))))

(defun utf8-push (cp out)
  ;; The UTF-8 bytes of code point CP onto the fill-pointer vector OUT.
  (let ((bytes (rontolisp:string-to-octets (string (code-char cp)))))
    (dotimes (i (length bytes)) (vector-push-extend (aref bytes i) out))))

(defun json-string ()
  ;; The string at *json-pos* (its opening quote), escapes resolved, as a
  ;; Lisp string; the fast path is a run of bytes with no escape, decoded in
  ;; one piece.
  (let* ((b *json-bytes*) (start (+ *json-pos* 1)) (i start) (plain t))
    ;; find the closing quote, noting whether any escape occurs
    (loop
      (let ((c (aref b i)))
        (cond ((= c 34) (return))
              ((= c 92)
               (setq plain nil)
               (setq i (+ i 2)))
              (t (setq i (+ i 1))))))
    (setq *json-pos* (+ i 1))
    (if plain
        (rontolisp:octets-to-string (subseq b start i))
        (let ((out
               (make-array (- i start)
                           :element-type '(unsigned-byte 8)
                           :fill-pointer 0
                           :adjustable t))
              (j start))
          (loop while (< j i)
                do
                  (let ((c (aref b j)))
                    (if (/= c 92)
                        (progn
                          (vector-push-extend c out)
                          (setq j (+ j 1)))
                        (let ((e (aref b (+ j 1))))
                          (setq j (+ j 2))
                          (cond ((= e 110) (vector-push-extend 10 out))
                                ((= e 116) (vector-push-extend 9 out))
                                ((= e 114) (vector-push-extend 13 out))
                                ((= e 98) (vector-push-extend 8 out))
                                ((= e 102) (vector-push-extend 12 out))
                                ((= e 117)
                                 (let ((cp (json-hex j)))
                                   (setq j (+ j 4))
                                   ;; a surrogate pair spelled as two escapes
                                   (when (and (>= cp 55296) (< cp 56320)
                                              (= (aref b j) 92)
                                              (= (aref b (+ j 1)) 117))
                                     (let ((lo (json-hex (+ j 2))))
                                       (setq cp
                                             (+ 65536 (* (- cp 55296) 1024)
                                                (- lo 56320)))
                                       (setq j (+ j 6))))
                                   (utf8-push cp out)))
                                (t (vector-push-extend e out)))))))
          ;; OUT is fill-pointer/adjustable, so COERCE (not SUBSEQ alone) is
          ;; what gets back to a packed vector (.kb/packed-integer-vectors.md).
          (rontolisp:octets-to-string
           (coerce out '(vector (unsigned-byte 8))))))))

(defun json-number ()
  (let* ((b *json-bytes*) (start *json-pos*) (i start) (float nil))
    (loop
      (let ((c (aref b i)))
        (if (or (and (>= c 48) (<= c 57)) (= c 45) (= c 43))
            (setq i (+ i 1))
            (if (or (= c 46) (= c 101) (= c 69))
                (progn
                  (setq float t)
                  (setq i (+ i 1)))
                (return)))))
    (setq *json-pos* i)
    (let ((text (rontolisp:octets-to-string (subseq b start i))))
      (if float (read-from-string text) (parse-integer text)))))

(defun json-value ()
  (json-skip-ws)
  (let ((c (aref *json-bytes* *json-pos*)))
    (cond ((= c 123) (json-object))
          ((= c 91) (json-array))
          ((= c 34) (json-string))
          ((= c 116)
           (setq *json-pos* (+ *json-pos* 4))
           t)
          ((= c 102)
           (setq *json-pos* (+ *json-pos* 5))
           nil)
          ((= c 110)
           (setq *json-pos* (+ *json-pos* 4))
           'null)
          (t (json-number)))))

(defun json-object ()
  (json-expect 123)
  (let ((table (make-hash-table :test 'equal)))
    (json-skip-ws)
    (if (= (aref *json-bytes* *json-pos*) 125)
        (progn
          (setq *json-pos* (+ *json-pos* 1))
          table)
        (loop
          (json-skip-ws)
          (let ((key (json-string)))
            (json-expect 58)
            (setf (gethash key table) (json-value)))
          (json-skip-ws)
          (let ((c (aref *json-bytes* *json-pos*)))
            (setq *json-pos* (+ *json-pos* 1))
            (cond ((= c 125) (return table))
                  ((/= c 44)
                   (error "json: expected , or } at byte ~a"
                          (- *json-pos* 1)))))))))

(defun json-array ()
  (json-expect 91)
  (let ((items '()))
    (json-skip-ws)
    (if (= (aref *json-bytes* *json-pos*) 93)
        (progn
          (setq *json-pos* (+ *json-pos* 1))
          (vector))
        (loop
          (push (json-value) items)
          (json-skip-ws)
          (let ((c (aref *json-bytes* *json-pos*)))
            (setq *json-pos* (+ *json-pos* 1))
            (cond ((= c 93) (return (coerce (nreverse items) 'vector)))
                  ((/= c 44)
                   (error "json: expected , or ] at byte ~a"
                          (- *json-pos* 1)))))))))

(defun json-parse-bytes (bytes)
  (let ((*json-bytes* bytes) (*json-pos* 0)) (json-value)))

(defun json-parse-file (path) (json-parse-bytes (read-file-bytes path)))

(defun token-name (v)
  ;; tokenizer_config.json spells a special token as a string or as an
  ;; object with a "content"; absent or null is nil.
  (cond ((stringp v) v) ((hash-table-p v) (gethash "content" v)) (t nil)))

(defun json-null-p (v) (or (null v) (eq v 'null)))

(defun split-regex-kind (regex fallback)
  ;; A Split pre-tokenizer's regex -> the kind whose scanner it is, told apart
  ;; by the one clause that differs (tokenizers.lisp lists them): the number
  ;; run `\p{N}{1,3}` is Llama 3's, a lone `\p{N}` Qwen 2.5 / 3's, and the
  ;; word class `[\p{L}\p{M}]+` Qwen 3.5's. FALLBACK for a regex none of
  ;; those matches -- the architecture row's kind, or nil.
  (cond ((search "[\\p{L}\\p{M}]+" regex) :qwen35)
        ((search "\\p{N}{1,3}" regex) :llama3)
        ((search "|\\p{N}|" regex) :qwen2)
        (t fallback)))

(defun hf-tokenizer-kind (json fallback)
  ;; The pre-tokenizer kind a tokenizer.json describes, read off its
  ;; pre_tokenizer block rather than assumed from the family: a Digits step in
  ;; front of ByteLevel is :smollm, ByteLevel with its own regex :gpt2, a Split
  ;; regex whichever scanner it spells. Nil when the file is not byte-level BPE
  ;; at all -- TinyLlama's is SentencePiece under the same "BPE" model type,
  ;; with no ByteLevel step -- which sends the caller to tokenizer.bin.
  (let ((pre (gethash "pre_tokenizer" json)))
    (if (json-null-p pre)
        nil
        (let ((steps
               (if (string= (gethash "type" pre) "Sequence")
                   (coerce (gethash "pretokenizers" pre) 'list)
                   (list pre)))
              (digits nil)
              (byte-level nil)
              (own-regex nil)
              (split nil))
          (dolist (step steps)
            (let ((type (gethash "type" step)))
              (cond ((string= type "Digits") (setq digits t))
                    ((string= type "ByteLevel")
                     (setq byte-level t)
                     (when (gethash "use_regex" step) (setq own-regex t)))
                    ((string= type "Split")
                     (setq split (gethash "Regex" (gethash "pattern" step)))))))
          (cond ((not byte-level) nil)
                (split (split-regex-kind split fallback))
                ((and own-regex digits) :smollm)
                (own-regex :gpt2)
                (t fallback))))))

(defun template-opens-with-special-p (processor)
  ;; Whether a post_processor (or one of a Sequence's) is a TemplateProcessing
  ;; whose single-sequence template starts with a special token.
  (let ((type (gethash "type" processor)))
    (cond ((string= type "Sequence")
           (some #'template-opens-with-special-p
                 (coerce (gethash "processors" processor) 'list)))
          ((string= type "TemplateProcessing")
           (let ((single (gethash "single" processor)))
             (and (> (length single) 0)
              (not (json-null-p (gethash "SpecialToken" (aref single 0)))))))
          (t nil))))

(defun hf-adds-bos-p (json config)
  ;; Whether encoding prepends BOS. tokenizer_config.json's add_bos_token says
  ;; so when it is there; otherwise it is the post_processor's business -- a
  ;; TemplateProcessing opening with a special token (Llama 3, LFM2). Qwen's
  ;; ByteLevel post-processor and SmolLM2's null add none, whatever bos_token
  ;; the config names (SmolLM2 names <|im_start|>, and prepending it would put
  ;; a second one in front of every chat turn).
  (multiple-value-bind (v present) (gethash "add_bos_token" config)
    (if (and present (not (json-null-p v)))
        v
        (let ((post (gethash "post_processor" json)))
          (and (not (json-null-p post))
               (template-opens-with-special-p post))))))

(defun load-hf-tokenizer (dir fallback-kind)
  ;; tokenizer.json (the vocab, the ranked merges, the added tokens) and
  ;; tokenizer_config.json (which added tokens are BOS and EOS) -> a byte-level
  ;; BPE tokenizer of the pre-tokenizer kind the file describes (FALLBACK-KIND,
  ;; the architecture row's, when it describes one this file cannot name), or
  ;; nil when the file is not byte-level BPE.
  (let* ((json (json-parse-file (concatenate 'string dir "tokenizer.json")))
         (kind (hf-tokenizer-kind json fallback-kind)))
    (and kind (load-hf-bpe-tokenizer dir json kind))))

(defun load-hf-bpe-tokenizer (dir json kind)
  (let* ((config
          (json-parse-file (concatenate 'string dir "tokenizer_config.json")))
         (vocab (gethash "vocab" (gethash "model" json)))
         (merges (gethash "merges" (gethash "model" json)))
         (added (gethash "added_tokens" json))
         (n 0))
    (maphash (lambda (k id) (when (>= id n) (setq n (+ id 1)))) vocab)
    (dotimes (i (length added))
      (let ((id (gethash "id" (aref added i))))
        (when (>= id n) (setq n (+ id 1)))))
    (let ((tokens (make-array n :initial-element "")) (specials '()))
      (maphash (lambda (k id) (setf (aref tokens id) k)) vocab)
      (dotimes (i (length added))
        (let ((a (aref added i)))
          (setf (aref tokens (gethash "id" a)) (gethash "content" a))
          (when (gethash "special" a) (push (gethash "content" a) specials))))
      (let ((bos
             (and (hf-adds-bos-p json config)
                  (token-name (gethash "bos_token" config))))
            (eos (token-name (gethash "eos_token" config))))
        (tokenizer:make-bpe tokens merges
         :kind kind
         :specials specials
         :bos (and bos (position bos tokens :test #'string=))
         :eos (and eos (position eos tokens :test #'string=)))))))

;;; --- printing tokens as they come ------------------------------------------------
;;; A token is bytes, and a character straddles two tokens routinely, so the
;;; loop accumulates bytes and prints what is complete: the streaming half of
;;; the tokenizer package (tokenizer:decode-bytes) plus the UTF-8 arithmetic.

(defun utf8-length (b)
  ;; The length of the sequence lead byte B starts; 1 for anything that is not
  ;; a lead byte, so a stray byte -- a real SentencePiece byte-fallback token
  ;; like <0xC0> is one -- is passed through immediately rather than held
  ;; waiting for continuation bytes that will never arrive. FRAMING, not
  ;; decoding: it only measures how many bytes a sequence claims, it never
  ;; assembles or inspects one (rontolisp:octets-to-string does that, once
  ;; complete-prefix below has found where it is safe to call it).
  (cond ((< b 128) 1)
        ((< b 194) 1)
        ((< b 224) 2)
        ((< b 240) 3)
        ((< b 245) 4)
        (t 1)))

(defun complete-prefix (pending)
  ;; How many bytes of PENDING form whole sequences.
  (let ((n (fill-pointer pending)) (i 0))
    (loop
      (when (>= i n) (return n))
      (let ((len (utf8-length (aref pending i))))
        (when (> (+ i len) n) (return i))
        (setq i (+ i len))))))

(defun print-complete (pending)
  ;; Print the complete prefix of PENDING (leniently when a byte is malformed)
  ;; and keep the incomplete tail for the next token.
  (let ((k (complete-prefix pending)) (n (fill-pointer pending)))
    (when (> k 0)
      (let ((bytes (make-array k :element-type '(unsigned-byte 8))))
        (dotimes (i k) (setf (aref bytes i) (aref pending i)))
        (write-string (rontolisp:octets-to-string bytes)))
      (dotimes (i (- n k)) (setf (aref pending i) (aref pending (+ k i))))
      (setf (fill-pointer pending) (- n k)))))

(defun push-token-bytes (pending tk token prev)
  ;; The bytes of TOKEN onto PENDING -- less the dummy prefix space a
  ;; SentencePiece piece carries right after BOS (run.c's decode rule).
  (let* ((bytes (tokenizer:decode-bytes tk (list token)))
         (start
          (if (and prev (eql prev (tokenizer:bos-id tk)) (> (length bytes) 0)
                   (= (aref bytes 0) 32))
              1
              0)))
    (do ((i start (+ i 1)))
        ((>= i (length bytes)))
      (vector-push-extend (aref bytes i) pending))))

;;; --- the state: KV cache and RoPE tables --------------------------------------

(defun layer-kind-count (layers kind)
  ;; How many layers of KIND the list has: the KV-cache slots (:attention) or
  ;; the recurrent states (:deltanet) it needs.
  (let ((n 0))
    (dotimes (i (length layers) n)
      (if (eq (getf (aref layers i) :kind) kind) (setq n (+ n 1))))))

(defun recurrent-states (layers)
  ;; What the non-attention mixers carry from token to token, one entry per
  ;; :deltanet / :shortconv layer, indexed by the layer's :slot.
  (let ((states
         (make-array
          (+ (layer-kind-count layers :deltanet)
             (layer-kind-count layers :shortconv)))))
    (dotimes (i (length layers) states)
      (let* ((layer (aref layers i)) (kind (getf layer :kind)))
        (cond ((eq kind :deltanet)
               (setf (aref states (getf layer :slot)) (deltanet-state layer)))
              ((eq kind :shortconv)
               (setf (aref states (getf layer :slot))
                     (shortconv-state layer))))))))

(defun make-state (model)
  ;; The KV cache: per attention layer, per kv-head, keys (seq-len x hs)
  ;; row-major and values (hs x seq-len) transposed -- see the header. Plus the
  ;; RoPE tables, which every layer that rotates at all shares, and the
  ;; recurrent state of every :deltanet / :shortconv layer.
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
          :recurrent (recurrent-states layers))))

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
            (deltanet-forward layer
                              (aref (getf state :recurrent) (getf layer :slot))
                              xb (getf model :eps)))
           ((eq kind :shortconv)
            (shortconv-forward layer
                               (aref (getf state :recurrent) (getf layer :slot))
                               xb))
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

(defun sample-argmax (logits n)
  ;; The largest of the first N logits (the vocabulary; the classifier may be
  ;; padded past it).
  (let ((best 0) (top (aref logits 0)))
    (dotimes (i n best)
      (let ((v (aref logits i)))
        (when (> v top)
          (setq top v)
          (setq best i))))))

(defun softmax-into-list (logits n temperature)
  ;; -> a list of (probability . id) over the first N logits, the vocabulary
  (let* ((top -1e30)
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

(defun sample (logits n)
  (if (= *temperature* 0)
      (sample-argmax logits n)
      (let ((probs (softmax-into-list logits n *temperature*))
            (coin (random-f32)))
        (if (or (<= *topp* 0) (>= *topp* 1))
            (sample-mult probs coin)
            (sample-topp probs *topp* coin)))))

;;; --- generate ------------------------------------------------------------------

(defun generate (model state tk prompt steps)
  (let* ((prompt-tokens (tokenizer:encode tk prompt :bos t))
         (token (first prompt-tokens))
         (rest (rest prompt-tokens))
         (n (min (tokenizer:vocabulary-size tk) (getf model :vocab)))
         ;; stop on the sequence delimiters: the tokenizer's BOS (llama2.c's
         ;; rule) and EOS, and the EOS the config.json names
         (stops
          (remove nil
                  (list (tokenizer:bos-id tk) (tokenizer:eos-id tk)
                        (getf model :eos))))
         (pending
          (make-array 16
                      :element-type '(unsigned-byte 8)
                      :fill-pointer 0
                      :adjustable t))
         (start nil)
         (pos 0))
    ;; run.c prints each token as it is fed back in, which never shows the
    ;; first one -- BOS, in every prompt it sees. A tokenizer that adds no BOS
    ;; (SmolLM2, Qwen) starts the prompt with a word, which is echoed here.
    (unless (or (eql token (tokenizer:bos-id tk)) (string= *mode* "chat"))
      (push-token-bytes pending tk token nil)
      (print-complete pending))
    (loop while (< pos steps)
          do
            (let* ((logits (forward model state token pos))
                   (prompted rest)
                   (next (if rest (pop rest) (sample logits n))))
              (setq pos (+ pos 1))
              (when *trace*
                (format *error-output* "~a:~a ~s~%" pos next
                        (rontolisp:octets-to-string
                         (coerce (tokenizer:decode-bytes tk (list next))
                                 '(vector (unsigned-byte 8))))))
              ;; a stop token ends the answer -- when the model produced it; the
              ;; prompt's own <|im_end|> is just the prompt
              (when (and (not prompted) (member next stops)) (return))
              ;; run.c echoes the prompt as it is consumed; a chat template is
              ;; not part of the answer, so -m chat prints the answer alone
              (unless (and prompted (string= *mode* "chat"))
                (push-token-bytes pending tk next token)
                (print-complete pending)
                (finish-output))
              (setq token next)
              (unless start (setq start (get-internal-real-time)))))
    (terpri)
    (when (and start (> pos 1))
      (format *error-output* "achieved tok/s: ~,2f~%"
       (/ (* (- pos 1) 1000.0) (max 1 (- (get-internal-real-time) start)))))))

;;; --- main ------------------------------------------------------------------------

(let* ((t0 (get-internal-real-time))
       (model (load-model *checkpoint*))
       (t1 (get-internal-real-time)))
  (when (or (<= *steps* 0) (> *steps* (getf model :seq-len)))
    (setq *steps* (getf model :seq-len)))
  (let* ((dir (checkpoint-directory *checkpoint*))
         (tk
          (or (cond ((and (getf model :gguf-tokenizer) (null (flag-value "-z")))
                     (load-gguf-tokenizer model))
                    ((and dir (null (flag-value "-z"))
                      (probe-file (concatenate 'string dir "tokenizer.json")))
                     ;; nil for a SentencePiece tokenizer.json (TinyLlama):
                     ;; that one is tokenizer.bin's shape
                     (load-hf-tokenizer dir (getf model :tokenizer)))
                    (t nil)) (load-tokenizer *tokenizer* (getf model :vocab))))
         (template
          ;; the family's, or ChatML for a checkpoint whose vocabulary has
          ;; its turn marker (SmolLM2 is model_type llama, and llama has none)
          (or (getf model :chat)
              (and (tokenizer:token-id tk "<|im_start|>") *chatml*)))
         (prompt
          (if (and (string= *mode* "chat") template)
              (format nil template *prompt*)
              *prompt*))
         (state (make-state model)))
    (format *error-output*
            "loaded ~a: dim=~a hidden=~a layers=~a heads=~a kv-heads=~a vocab=~a seq-len=~a in ~a ms (tokenizer + kv cache ~a ms)~%"
            *checkpoint* (getf model :dim) (getf model :hidden)
            (getf model :n-layers) (getf model :n-heads)
            (getf model :n-kv-heads) (getf model :vocab) (getf model :seq-len)
            (- t1 t0) (- (get-internal-real-time) t1))
    (setq *rng-state* (logand *seed* +mask64+))
    (generate model state tk prompt *steps*)))
