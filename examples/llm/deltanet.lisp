;;;; The Gated DeltaNet mixer: the :deltanet layer kind of llm.lisp's table.
;;;;
;;;; Qwen3.5 (and 3.6 / 3.8, the same architecture) replaces three of every four
;;;; attention layers with a gated linear recurrence: per head a k x v state
;;;; matrix S that decays a little every token, is corrected toward the current
;;;; value along the current key (the delta rule), and is read out along the
;;;; query. Nothing grows with the context -- the state is the whole memory --
;;;; so a Gated DeltaNet layer has no KV cache, only S and a short window for
;;;; the causal convolution in front of it.
;;;;
;;;; This file is the single-token path of transformers' modeling_qwen3_5.py
;;;; (read 2026-09-03): Qwen3_5GatedDeltaNet.forward under a cache, i.e.
;;;; causal_conv1d_update + torch_recurrent_gated_delta_rule +
;;;; Qwen3_5RMSNormGated. Prefill is the same recurrence run token by token,
;;;; which is what a decode-only engine does anyway. It is its own file so that
;;;; deltanet-check.lisp can pin the arithmetic against a transcription of the
;;;; PyTorch reference without loading the engine (llm.lisp runs a model
;;;; when it is loaded).
;;;;
;;;; THE DECODE STEP, per token, from the normed hidden x (dim):
;;;;
;;;;   1. qkv = Wqkv x (q | k | v, heads x kd each side, heads x vd for v),
;;;;      z = Wz x (heads x vd), b = Wb x, a = Wa x (one per head).
;;;;   2. A causal depthwise convolution over the last KERNEL qkv vectors
;;;;      (causal-conv.lisp; the previous kernel-1 are the layer's window
;;;;      state), then SiLU.
;;;;   3. Per head: L2-normalise q and k (eps 1e-6), q /= sqrt(kd);
;;;;      beta = sigmoid(b), g = -exp(A_log) * softplus(a + dt_bias),
;;;;      decay = exp(g).
;;;;   4. Per head, with S (stored TRANSPOSED, vd x kd, so both reads are one
;;;;      vec:matvec each): kv = S k over the decayed state; delta =
;;;;      (v - kv) * beta; S <- decay * S + delta (x) k; o = S q.
;;;;   5. Gated RMSNorm per head: o = RMSNorm(o) * w * silu(z_head) -- the norm
;;;;      first, then the gate.
;;;;   6. Wo over the heads x vd concatenation; the caller adds the residual.
;;;;
;;;; Step 4's decay and rank-1 update are ONE typed dotimes over the vd x kd
;;;; state (S[j][i] <- S[j][i] * decay + delta[j] * k[i]), which the JVM
;;;; compiles to a primitive loop (.kb/jvm-typed-loops.md); kv is read from the
;;;; undecayed state and scaled after, which is the same product in a different
;;;; rounding order. There is no vec: member for a rank-1 update today; whether
;;;; one is worth adding is a measurement (the llama2 README has it).
;;;;
;;;; WEIGHTS (the per-layer vectors the reader hands transformer-layers), HF
;;;; name first, GGUF (general.architecture qwen35) second:
;;;;
;;;;   :ssm-qkv      in_proj_qkv [2*key_dim+value_dim, dim]   attn_qkv
;;;;   :ssm-z        in_proj_z   [value_dim, dim]             attn_gate
;;;;   :ssm-beta     in_proj_b   [heads, dim]                 ssm_beta
;;;;   :ssm-alpha    in_proj_a   [heads, dim]                 ssm_alpha
;;;;   :ssm-conv     conv1d.weight [conv_dim, 1, kernel] squeezed to a rank-2
;;;;                 conv_dim x kernel matrix                 ssm_conv1d (F32)
;;;;   :ssm-a        -exp(A_log) per head -- what the step uses. GGUF stores
;;;;                 exactly that (ssm_a); a safetensors reader negates the
;;;;                 exp of A_log
;;;;   :ssm-dt-bias  dt_bias [heads]                          ssm_dt.bias
;;;;   :ssm-norm     norm.weight [vd] -- RAW: unlike every other Qwen3.5 norm
;;;;                 this one is not stored as an offset from 1 (see the
;;;;                 architecture table in llm.lisp)   ssm_norm (F32)
;;;;   :ssm-out      out_proj [dim, value_dim]                ssm_out
;;;;
;;;; heads, kd and vd are read off the shapes: heads = (length :ssm-a), vd =
;;;; (length :ssm-norm), kd from the rows of :ssm-qkv. The published dense
;;;; models have as many key heads as value heads; the repeat_interleave of a
;;;; model with fewer key heads (Qwen3-Next) is not implemented.

(require :causal-conv "causal-conv.lisp")

;;; --- the layer and its state -------------------------------------------------

(defun deltanet-layer (weights l slot)
  ;; The layer-L :deltanet entry of the table, from the per-layer weight
  ;; vectors above. SLOT is its index into the state vector make-state builds.
  (let* ((qkv (aref (getf weights :ssm-qkv) l))
         (a (aref (getf weights :ssm-a) l))
         (gnorm (aref (getf weights :ssm-norm) l))
         (heads (length a))
         (vd (length gnorm))
         (conv-dim (array-dimension qkv 0))
         (kd (floor (- conv-dim (* heads vd)) (* 2 heads))))
    (list :kind :deltanet
          :slot slot
          :norm (aref (getf weights :rms-att) l)
          :heads heads
          :kd kd
          :vd vd
          :conv-dim conv-dim
          :wqkv qkv
          :wz (aref (getf weights :ssm-z) l)
          :wb (aref (getf weights :ssm-beta) l)
          :wa (aref (getf weights :ssm-alpha) l)
          :conv (aref (getf weights :ssm-conv) l)
          :a a
          :dt-bias (aref (getf weights :ssm-dt-bias) l)
          :gnorm gnorm
          :wo (aref (getf weights :ssm-out) l))))

(defun deltanet-state (layer)
  ;; What one :deltanet layer carries from token to token: the previous
  ;; kernel-1 qkv vectors (oldest row first) and, per head, the vd x kd state
  ;; S^T -- plus the per-token scratch vectors, allocated once.
  (let* ((heads (getf layer :heads))
         (kd (getf layer :kd))
         (vd (getf layer :vd))
         (conv-dim (getf layer :conv-dim))
         (s (make-array heads)))
    (dotimes (h heads)
      (setf (aref s h) (linalg:zeros (list vd kd) :element-type 'single-float)))
    (list :window (conv-window (getf layer :conv))
          :s s
          :xc (vec:zeros conv-dim :element-type 'single-float)
          :q (vec:zeros kd :element-type 'single-float)
          :k (vec:zeros kd :element-type 'single-float)
          :v (vec:zeros vd :element-type 'single-float)
          :o (vec:zeros vd :element-type 'single-float)
          :out (vec:zeros (* heads vd) :element-type 'single-float))))

;;; --- the pieces --------------------------------------------------------------

(defun scalar-sigmoid (x) (/ 1.0 (+ 1.0 (exp (- x)))))

(defun softplus (x)
  ;; log(1 + exp(x)), with torch's threshold: the identity above 20.
  (if (> x 20.0) x (log (+ 1.0 (exp x)))))

(defun silu-in-place (v)
  ;; x * sigmoid(x) over every element, in place.
  (dotimes (c (length v))
    (let ((u (aref v c))) (setf (aref v c) (/ u (+ 1.0 (exp (- u))))))))

(defun l2-normalize (v)
  ;; v / sqrt(sum v^2 + eps), in place -- FLA's l2norm, eps 1e-6.
  (vec:scale-into v v (/ 1.0 (sqrt (+ (vec:dot v v) 0.000001)))))

(defun gated-delta-rule (s q k v g beta o)
  ;; One head, one token of torch_recurrent_gated_delta_rule with
  ;; use_qk_l2norm_in_kernel: Q and K are normalised IN PLACE, S (vd x kd, the
  ;; transposed state) is updated in place, and the output lands in O.
  (let ((kd (length q)) (vd (length v)))
    (l2-normalize q)
    (l2-normalize k)
    (vec:scale-into q q (/ 1.0 (sqrt kd)))
    (let ((decay (exp g)) (kv (vec:matvec s k)))
      ;; delta = (v - decay * S_old k) * beta, and S <- decay * S + delta (x) k
      ;; in the same pass over the state
      (dotimes (j vd)
        (let ((d (* (- (aref v j) (* decay (aref kv j))) beta)))
          (dotimes (i kd)
            (setf (aref s j i) (+ (* (aref s j i) decay) (* d (aref k i)))))))
      (vec:matvec-into o s q))))

(defun gated-rmsnorm-into (out base o g z vd eps)
  ;; Qwen3_5RMSNormGated over one head: out[base..] = RMSNorm(o) * g *
  ;; silu(z[base..]) -- the norm before the gate.
  (let ((ss 0.0))
    (dotimes (i vd) (let ((x (aref o i))) (setq ss (+ ss (* x x)))))
    (let ((scale (/ 1.0 (sqrt (+ (/ ss vd) eps)))))
      (dotimes (i vd)
        (let ((zi (aref z (+ base i))))
          (setf (aref out (+ base i))
           (* (aref o i) scale (aref g i) (/ zi (+ 1.0 (exp (- zi)))))))))))

;;; --- the decode step -----------------------------------------------------------

(defun deltanet-forward (layer st xb eps)
  ;; The whole mixer over the normed input XB; returns what the residual adds.
  (let* ((heads (getf layer :heads))
         (kd (getf layer :kd))
         (vd (getf layer :vd))
         (key-dim (* heads kd))
         (a (getf layer :a))
         (dt-bias (getf layer :dt-bias))
         (gnorm (getf layer :gnorm))
         (qkv (vec:matvec (getf layer :wqkv) xb))
         (z (vec:matvec (getf layer :wz) xb))
         (b (vec:matvec (getf layer :wb) xb))
         (al (vec:matvec (getf layer :wa) xb))
         (xc (getf st :xc))
         (s (getf st :s))
         (q (getf st :q))
         (k (getf st :k))
         (v (getf st :v))
         (o (getf st :o))
         (out (getf st :out)))
    (causal-conv (getf st :window) (getf layer :conv) qkv xc)
    (silu-in-place xc)
    (dotimes (h heads)
      (let ((qb (* h kd))
            (kb (+ key-dim (* h kd)))
            (vb (+ (* 2 key-dim) (* h vd))))
        (dotimes (i kd)
          (setf (aref q i) (aref xc (+ qb i)))
          (setf (aref k i) (aref xc (+ kb i))))
        (dotimes (i vd) (setf (aref v i) (aref xc (+ vb i))))
        (let ((beta (scalar-sigmoid (aref b h)))
              (g (* (aref a h) (softplus (+ (aref al h) (aref dt-bias h))))))
          (gated-delta-rule (aref s h) q k v g beta o)
          (gated-rmsnorm-into out (* h vd) o gnorm z vd eps))))
    (vec:matvec (getf layer :wo) out)))
