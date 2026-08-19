;; transformer/transformer.lisp -- llm_from_scratch/transformer/transformer.py,
;; ported.
;;
;; The encoder/decoder Transformer of chapter 2: an encoder block (self
;; attention + feed forward, each wrapped in a residual and a LayerNorm), a
;; decoder block (masked self attention, source-target attention, feed forward),
;; the two stacks, the whole model, and the greedy `transformer-inference' loop.
;;
;; nn.Sequential is `torch:sequential' and nn.ReLU is `(function torch:relu)' --
;; torch:forward applies a plain function as readily as a module, so no
;; activation-module type exists in this package.

(load "attention.lisp")
(load "utils.lisp")

(defun encoder-block-forward (self x &optional src-padding-mask)
  (let* ((attended
          (torch:forward (torch:field self :layer-norm1)
                         (torch:add x
                                    (torch:forward (torch:field self :attention)
                                                   x x x src-padding-mask))))
         (fed
          (torch:forward (torch:field self :layer-norm2)
                         (torch:add attended
                                    (torch:forward
                                     (torch:field self :feed-forward)
                                     attended)))))
    fed))

(defun feed-forward-block (d-model d-ff)
  (torch:sequential (torch:linear d-model d-ff) (function torch:relu)
                    (torch:linear d-ff d-model)))

(defun encoder-block (d-model n-heads d-k d-v d-ff)
  (torch:module :encoder-block (list
                                :attention
                                (multi-head-attention n-heads d-k d-v d-model)
                                :layer-norm1 (torch:layer-norm d-model)
                                :feed-forward (feed-forward-block d-model d-ff)
                                :layer-norm2 (torch:layer-norm d-model))
                (function encoder-block-forward)))

(defun encoder-forward (self x &optional src-padding-mask)
  (let ((out
         (torch:forward (torch:field self :pe)
                        (torch:forward (torch:field self :embedding) x))))
    (dolist (block (torch:field self :blocks) out)
      (setq out (torch:forward block out src-padding-mask)))))

(defun encoder
    (vocabulary-size max-sequence-len d-model n-blocks n-heads d-k d-v d-ff)
  (let ((blocks nil))
    (dotimes (i n-blocks)
      (setq blocks (cons (encoder-block d-model n-heads d-k d-v d-ff) blocks)))
    (torch:module :encoder (list
                            :embedding (torch:embedding vocabulary-size d-model)
                            :pe (positional-encoding d-model max-sequence-len)
                            :blocks (reverse blocks))
                  (function encoder-forward))))

(defun decoder-block-forward
    (self x encoder-output &optional tgt-mask src-tgt-padding-mask)
  (let* ((self-attended
          (torch:forward (torch:field self :layer-norm1)
                         (torch:add x
                                    (torch:forward (torch:field self :attention)
                                                   x x x tgt-mask))))
         (cross-attended
          (torch:forward (torch:field self :layer-norm2)
                         (torch:add self-attended
                                    (torch:forward
                                     (torch:field self :attention-source-target)
                                     self-attended encoder-output encoder-output
                                     src-tgt-padding-mask))))
         (fed
          (torch:forward (torch:field self :layer-norm3)
                         (torch:add cross-attended
                                    (torch:forward
                                     (torch:field self :feed-forward)
                                     cross-attended)))))
    fed))

(defun decoder-block (d-model n-heads d-k d-v d-ff)
  (torch:module :decoder-block (list
                                :attention
                                (multi-head-attention n-heads d-k d-v d-model)
                                :layer-norm1 (torch:layer-norm d-model)
                                :attention-source-target
                                (multi-head-attention n-heads d-k d-v d-model)
                                :layer-norm2 (torch:layer-norm d-model)
                                :feed-forward (feed-forward-block d-model d-ff)
                                :layer-norm3 (torch:layer-norm d-model))
                (function decoder-block-forward)))

(defun decoder-forward
    (self x encoder-output &optional tgt-mask src-tgt-padding-mask)
  (let ((out
         (torch:forward (torch:field self :pe)
                        (torch:forward (torch:field self :embedding) x))))
    (dolist (block (torch:field self :blocks) out)
      (setq out
            (torch:forward block out encoder-output tgt-mask
                           src-tgt-padding-mask)))))

(defun decoder
    (vocabulary-size max-sequence-len d-model n-blocks n-heads d-k d-v d-ff)
  (let ((blocks nil))
    (dotimes (i n-blocks)
      (setq blocks (cons (decoder-block d-model n-heads d-k d-v d-ff) blocks)))
    (torch:module :decoder (list
                            :embedding (torch:embedding vocabulary-size d-model)
                            :pe (positional-encoding d-model max-sequence-len)
                            :blocks (reverse blocks))
                  (function decoder-forward))))

(defun transformer-forward
    (self src tgt &optional src-mask tgt-mask src-tgt-mask)
  (torch:forward (torch:field self :linear)
                 (torch:forward (torch:field self :decoder) tgt
                  (torch:forward (torch:field self :encoder) src src-mask)
                  tgt-mask src-tgt-mask)))

(defun transformer (src-vocab-size tgt-vocab-size max-sequence-len d-model
                                   n-blocks n-heads d-k d-v d-ff)
  ;; The whole model: an encoder over the source, a decoder over the target so
  ;; far, and one bias-free projection onto the target vocabulary.
  (torch:module :transformer (list :encoder
                                   (encoder src-vocab-size max-sequence-len
                                            d-model n-blocks n-heads d-k d-v
                                            d-ff)
                                   :decoder
                                   (decoder tgt-vocab-size max-sequence-len
                                            d-model n-blocks n-heads d-k d-v
                                            d-ff)
                                   :linear
                                   (torch:linear d-model tgt-vocab-size
                                                 :bias nil))
                (function transformer-forward)))

(defun transformer-inference
    (model src bos-token eos-token &key (max-length 20))
  ;; Greedy decoding, as a LIST of token ids beginning with bos-token: encode
  ;; the source once, then extend the target one argmax at a time until
  ;; eos-token or max-length. Wrapped in torch:no-grad -- nothing here needs a
  ;; tape, and building one would retain every intermediate.
  (torch:no-grad
    (let ((tokens (list bos-token))
          (encoder-output (torch:forward (torch:field model :encoder) src)))
      (dotimes (step max-length)
        (let* ((logits
                (torch:forward (torch:field model :linear)
                               (torch:forward (torch:field model :decoder)
                                              (torch:pad-sequence (list tokens))
                                              encoder-output)))
               (shape (torch:shape logits))
               (next
                (truncate
                 (torch:argmax
                  (torch:reshape (torch:slice logits
                                              (list nil
                                                    (list (- (cadr shape) 1)
                                                          (cadr shape)) nil))
                                 (list (caddr shape)))))))
          (setq tokens (append tokens (list next)))
          (when (= next eos-token) (return))))
      tokens)))
