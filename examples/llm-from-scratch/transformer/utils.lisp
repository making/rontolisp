;; transformer/utils.lisp -- llm_from_scratch/transformer/utils.py, ported.
;;
;; Layer normalization and the sinusoidal positional encoding. The book's
;; free function layer_norm() is `layer-norm' below; its LayerNorm *class* is
;; the library's `torch:layer-norm' module, which is nn.LayerNorm's
;; (x - mean) / sqrt(var + eps) rather than the book's (x - mean) / (std + eps)
;; -- the two agree to eps, and the library follows PyTorch.
;;
;; Loaded by transformer.lisp and by the chapter02 sections; it defines no
;; top-level effect of its own.

(defun layer-norm (x &key (eps 1.0e-6))
  ;; The book's functional layer_norm: the last axis centred and divided by its
  ;; BIASED standard deviation (unbiased=False, i.e. :ddof 0). Composed from
  ;; torch ops, so it is differentiable end to end.
  (torch:div (torch:sub x (torch:mean x :axis -1 :keepdims t))
             (torch:add (torch:std x :axis -1 :keepdims t :ddof 0) eps)))

(defun sinusoidal-position-encoding (d-model sequence-length)
  ;; The "Attention Is All You Need" positional encoding as a RAW linalg array
  ;; of shape (1 sequence-length d-model) -- a constant buffer, so no tensor
  ;; and no gradient:
  ;;
  ;;   pe[pos, 2i]     = sin(pos / 10000^(2i / d_model))
  ;;   pe[pos, 2i + 1] = cos(pos / 10000^(2i / d_model))
  ;;
  ;; The table stays DOUBLE (linalg's default) deliberately, where a torch
  ;; tensor would be single-float: chapter02/section3.lisp checks that two rows'
  ;; dot product depends only on their offset to within 1e-6, and at this width
  ;; (d_model 128, dot products near 64) that bound is inside f32's own
  ;; resolution. The cost is that adding this buffer to single-float activations
  ;; is a MIXED-width pair, which --simd declines -- one broadcast add per
  ;; forward, which is the trade this example makes for the book's claim.
  (let ((pe (linalg:zeros (list sequence-length d-model))))
    (dotimes (pos sequence-length)
      (do ((i 0 (+ i 2)))
          ((>= i d-model))
        (let ((angle (/ pos (expt 10000.0 (/ (* 1.0 i) d-model)))))
          (setf (aref pe pos i) (sin angle))
          (when (< (+ i 1) d-model) (setf (aref pe pos (+ i 1)) (cos angle))))))
    (linalg:expand-dims pe 0)))

(defun positional-encoding-forward (self x)
  ;; x * sqrt(d_model) + pe[:, :sequence_length]. The encoding is sliced to the
  ;; batch's own length and broadcasts over the batch axis.
  (let ((sequence-length (cadr (torch:shape x))))
    (torch:add (torch:mul x (torch:field self :scale))
               (linalg:slice (torch:field self :pe)
                             (list nil (list 0 sequence-length))))))

(defun positional-encoding (d-model max-sequence-length)
  ;; The PositionalEncoding module: the encoding table lives in the :pe field as
  ;; a raw array, which is exactly PyTorch's register_buffer -- torch:parameters
  ;; walks field VALUES and collects only parameter tensors, so a buffer is
  ;; reached by nothing and trained by nothing.
  (torch:module :positional-encoding (list :pe
                                           (sinusoidal-position-encoding d-model
                                            max-sequence-length)
                                           :scale (sqrt (* 1.0 d-model)))
                (function positional-encoding-forward)))
