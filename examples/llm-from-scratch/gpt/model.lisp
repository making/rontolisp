;; gpt/model.lisp -- llm_from_scratch/gpt/model.py, ported.
;;
;; The GPT architecture: chapter 2's multi-head attention under a CAUSAL mask, a
;; pre-LayerNorm transformer block, the decoder-only stack with LEARNED position
;; embeddings, and autoregressive sampling. Three things differ from chapter 2's
;; encoder/decoder model and this file is where each of them lives -- the mask,
;; the learned positions, and the norm moving to the FRONT of each sublayer.
;;
;; Two shapes of the book's code do not survive the port, and both are named
;; here rather than hidden:
;;
;;   * `forward(idx, targets=None)` returns the (logits, loss) TUPLE. Here
;;     gpt-forward answers the logits and gpt-loss answers the loss, because a
;;     forward whose result depends on whether an optional argument was passed
;;     is a tuple only Python's caller can destructure cheaply.
;;   * `self.apply(self._init_weights)` walks the module tree by isinstance.
;;     gpt-apply walks torch:fields and dispatches on torch:module-kind, which
;;     is the same walk over the plist that IS a module's registration.

(load "../transformer/attention.lisp")

(defparameter *init-std* 0.02)

;; --- causal (masked) self attention -----------------------------------------

(defun gpt-attention-forward (self x)
  ;; Self attention with query = key = value = x under the look-ahead mask, then
  ;; the residual dropout. torch:subsequent-mask is (1 seq seq) and broadcasts
  ;; over the batch, so the book's unsqueeze(0).expand(B, -1, -1) is nothing the
  ;; port has to spell.
  (torch:forward (torch:field self :resid-dropout)
                 (torch:forward (torch:field self :attention) x x x
                  (torch:subsequent-mask (cadr (torch:shape x))))))

(defun gpt-attention (n-embd n-head &key (dropout 0.1))
  ;; GPTMultiHeadAttention: chapter 2's multi-head-attention, with the head
  ;; width n-embd / n-head, plus a dropout on its output.
  (unless (= 0 (mod n-embd n-head))
    (error "gpt: n-embd must be a multiple of n-head"))
  (let ((d-k (/ n-embd n-head)))
    (torch:module :gpt-attention (list :n-head n-head
                                       :n-embd n-embd
                                       :attention
                                       (multi-head-attention n-head d-k d-k
                                                             n-embd)
                                       :resid-dropout (torch:dropout dropout))
                  (function gpt-attention-forward))))

;; --- the transformer block (pre-LN) -----------------------------------------

(defun gpt-block-forward (self x)
  ;; x + attn(ln1(x)), then + mlp(ln2(x)): the norm sits BEFORE each sublayer
  ;; and the residual path stays unnormalized all the way through, which is
  ;; what makes a deep stack trainable without a warmup on the residual scale.
  ;; Chapter 2's encoder block normalizes AFTER the addition instead.
  (let ((attended
         (torch:add x
                    (torch:forward (torch:field self :attn)
                     (torch:forward (torch:field self :ln-1) x)))))
    (torch:add attended
               (torch:forward (torch:field self :mlp)
                (torch:forward (torch:field self :ln-2) attended)))))

(defun gpt-block (n-embd n-head &key (dropout 0.1))
  ;; TransformerBlock: attention and a 4x-wide GELU feed-forward, each behind
  ;; its own LayerNorm and inside its own residual.
  (torch:module :gpt-block (list :ln-1 (torch:layer-norm n-embd)
                            :attn (gpt-attention n-embd n-head :dropout dropout)
                            :ln-2 (torch:layer-norm n-embd)
                            :mlp (torch:sequential
                                  (torch:linear n-embd (* 4 n-embd))
                                  (function torch:gelu)
                                  (torch:linear (* 4 n-embd) n-embd)
                                  (torch:dropout dropout)))
                (function gpt-block-forward)))

;; --- the model ---------------------------------------------------------------

(defun gpt-forward (self idx)
  ;; (batch seq) token ids -> (batch seq vocab) logits. The position embedding
  ;; is looked up for 0..seq-1 and broadcasts over the batch, which is the
  ;; book's arange(T).unsqueeze(0).
  (let* ((seq-len (cadr (torch:shape idx)))
         (x
          (torch:forward (torch:field self :drop)
                         (torch:add (torch:forward
                                     (torch:field self :token-embedding) idx)
                                    (torch:forward
                                     (torch:field self :position-embedding)
                                     (linalg:arange seq-len))))))
    (dolist (block (torch:field self :blocks)) (setq x (torch:forward block x)))
    (torch:forward (torch:field self :head)
                   (torch:forward (torch:field self :ln-f) x))))

(defun gpt-loss (model idx targets)
  ;; The training objective: cross entropy of the (batch seq vocab) logits
  ;; against the (batch seq) next tokens. torch:cross-entropy-loss flattens the
  ;; leading axes itself, so the book's two view() calls have nothing to do.
  (torch:cross-entropy-loss (gpt-forward model idx) targets))

(defun gpt (vocab-size &key (n-embd 768) (n-layer 12) (n-head 12)
                       (block-size 1024) (dropout 0.1))
  ;; The GPT language model: a token table, a LEARNED position table (chapter 2
  ;; used a fixed sinusoid), n-layer blocks, a final norm and a bias-free
  ;; projection onto the vocabulary. Weights are re-initialized the book's way
  ;; before it is returned.
  (let ((blocks nil))
    (dotimes (i n-layer)
      (setq blocks (cons (gpt-block n-embd n-head :dropout dropout) blocks)))
    (let ((model
           (torch:module :gpt (list :block-size block-size
                               :n-embd n-embd
                               :token-embedding
                               (torch:embedding vocab-size n-embd)
                               :position-embedding
                               (torch:embedding block-size n-embd)
                               :drop (torch:dropout dropout)
                               :blocks (reverse blocks)
                               :ln-f (torch:layer-norm n-embd)
                               :head (torch:linear n-embd vocab-size :bias nil))
                         (function gpt-forward))))
      (gpt-apply model (function gpt-init-module))
      model)))

;; --- weight initialization (nn.Module.apply) --------------------------------

(defun gpt-apply (v fn)
  ;; nn.Module.apply: fn over every module reachable from v -- through the
  ;; field VALUES (torch:fields) and through any list of submodules in one --
  ;; children before their parent, like PyTorch. A field holding something that
  ;; is neither a module nor a list is simply not a module and is skipped.
  (cond ((torch:modulep v)
         (do ((p (torch:fields v) (cddr p)))
             ((null p))
           (gpt-apply (cadr p) fn))
         (funcall fn v))
        ((consp v) (dolist (e v) (gpt-apply e fn)))))

(defun gpt-normal-init (parameter)
  ;; torch.nn.init.normal_(w, mean=0.0, std=0.02) over a parameter's own shape.
  ;; :element-type 'single-float because torch: builds its tensors single-float
  ;; (torch.float32): a raw linalg array handed to torch:set-data keeps its OWN
  ;; width, and a double one here would leave every later kernel a mixed-width
  ;; pair, which --simd declines.
  (torch:set-data parameter
                  (linalg:mul *init-std*
                              (linalg:randn (torch:shape parameter)
                                            :element-type 'single-float))))

(defun gpt-init-module (m)
  ;; The book's _init_weights, dispatched on torch:module-kind rather than on
  ;; isinstance: a linear layer gets N(0, 0.02) weights and a zero bias, an
  ;; embedding table N(0, 0.02), a LayerNorm a unit gain and a zero bias.
  ;; Every other kind -- dropout, sequential, the blocks, the model itself --
  ;; owns no parameter of its own and is left alone.
  (let ((kind (torch:module-kind m)))
    (cond ((eq kind :linear)
           (gpt-normal-init (torch:field m :weight))
           (let ((b (torch:field m :bias)))
             (unless (null b)
               (torch:set-data b
                (linalg:zeros (torch:shape b) :element-type 'single-float)))))
          ((eq kind :embedding) (gpt-normal-init (torch:field m :weight)))
          ((eq kind :layer-norm)
           (torch:set-data (torch:field m :weight)
                           (linalg:ones (torch:shape (torch:field m :weight))
                                        :element-type 'single-float))
           (torch:set-data (torch:field m :bias)
                           (linalg:zeros (torch:shape (torch:field m :bias))
                                         :element-type 'single-float))))))

;; --- generation --------------------------------------------------------------

(defun gpt-top-k-filter (logits k)
  ;; The book's top-k step: every logit below the row's k-th largest becomes
  ;; -infinity, so the softmax gives it weight exactly 0 and only the k best
  ;; tokens can be drawn.
  (let* ((n (car (last (torch:shape logits))))
         (kk (min k n))
         (top (torch:topk logits kk))
         (threshold (linalg:slice top (list nil (list (- kk 1) kk)))))
    (torch:masked-fill logits (linalg:less (torch:data logits) threshold)
                       *neg-infinity*)))

(defun gpt-generate (model tokens max-new-tokens &key (temperature 1.0) top-k)
  ;; Autoregressive sampling, as a LIST of token ids beginning with the prompt:
  ;; predict, keep the LAST position's logits, divide by the temperature,
  ;; optionally keep only the top k, then draw from the softmax. The context is
  ;; cropped to the model's block-size, so the loop never outruns the position
  ;; table. Wrapped in torch:no-grad, like the book's @torch.no_grad().
  (torch:no-grad
    (let ((block-size (torch:field model :block-size)) (out tokens))
      (dotimes (step max-new-tokens out)
        (let* ((context
                (if (<= (length out) block-size) out (last out block-size)))
               (logits (gpt-forward model (torch:pad-sequence (list context))))
               (shape (torch:shape logits))
               (row
                (torch:div (torch:reshape (torch:slice logits
                                                       (list nil
                                                             (list
                                                              (- (cadr shape) 1)
                                                              (cadr shape))
                                                             nil))
                                          (list 1 (caddr shape))) temperature))
               (filtered (if (null top-k) row (gpt-top-k-filter row top-k)))
               (probs (torch:softmax filtered :axis -1)))
          (setq out
                (append out
                 (list (truncate (aref (torch:multinomial probs) 0 0))))))))))

;; --- the configuration record -----------------------------------------------

(defun gpt-config-model-size (self)
  ;; GPTConfig.get_model_size: the parameter count in MILLIONS, computed from
  ;; the shapes alone -- so a configuration can be sized before anything is
  ;; allocated. Both embedding tables, then per block the four attention
  ;; projections, the two feed-forward matrices and the two LayerNorms, then
  ;; the final norm and the output projection.
  (let* ((vocab (torch:field self :vocab-size))
         (n-embd (torch:field self :n-embd))
         (n-layer (torch:field self :n-layer))
         (block-size (torch:field self :block-size))
         (per-block
          (+ (* n-embd n-embd 4) (* n-embd n-embd 4) (* 4 n-embd n-embd)
             (* n-embd 4)))
         (params
          (+ (* vocab n-embd) (* block-size n-embd) (* per-block n-layer)
             (* n-embd 2) (* n-embd vocab))))
    (/ params 1000000.0)))

(defun gpt-config (&key (vocab-size 50257) (n-embd 768) (n-layer 12) (n-head 12)
                        (block-size 1024) (dropout 0.1))
  ;; The model's hyper-parameters as a module of pure fields -- no parameter, so
  ;; torch:parameters walks it and collects nothing. Its forward is
  ;; gpt-config-model-size, so (torch:forward config) sizes it.
  (torch:module :gpt-config (list :vocab-size vocab-size
                                  :n-embd n-embd
                                  :n-layer n-layer
                                  :n-head n-head
                                  :block-size block-size
                                  :dropout dropout)
                (function gpt-config-model-size)))

(defun gpt-from-config (config)
  ;; The model a configuration describes.
  (gpt (torch:field config :vocab-size)
       :n-embd (torch:field config :n-embd)
       :n-layer (torch:field config :n-layer)
       :n-head (torch:field config :n-head)
       :block-size (torch:field config :block-size)
       :dropout (torch:field config :dropout)))
