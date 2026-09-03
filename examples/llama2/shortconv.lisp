;;;; The LFM2 gated short-conv mixer: the :shortconv layer kind of llama2.lisp's
;;;; table (LFM2 / LFM2.5, `general.architecture` / `model_type` lfm2).
;;;;
;;;; Ten of LFM2.5-1.2B's sixteen blocks mix tokens with the simplest hybrid
;;;; layer in the field: a gated depthwise convolution of kernel 3, no matrix
;;;; state and no activation -- transformers' Lfm2ShortConv (modeling_lfm2.py,
;;;; read 2026-09-03), the single-token path under a cache. Per token, from the
;;;; normed hidden x (dim):
;;;;
;;;;   1. BCx = in_proj x (3 x dim), split in that order into B, C and x'.
;;;;   2. h = B * x'; c = the causal conv of kernel L over the last L h
;;;;      vectors (causal-conv.lisp; the previous L-1 are the layer's state).
;;;;   3. y = C * c; out_proj y is what the residual adds.
;;;;
;;;; WEIGHTS (the per-layer vectors the reader hands transformer-layers), HF
;;;; name first, GGUF second; the layer's norm is :rms-att (operator_norm /
;;;; attn_norm), like the attention layers':
;;;;
;;;;   :conv-in   conv.in_proj  [3*dim, dim]                  shortconv.in_proj
;;;;   :conv-w    conv.conv.weight [dim, 1, L] squeezed to a rank-2
;;;;              dim x L matrix                              shortconv.conv
;;;;   :conv-out  conv.out_proj [dim, dim]                    shortconv.out_proj
;;;;
;;;; LFM2.5's conv_bias is false, and the bias of a checkpoint that set it is
;;;; not implemented.

(require :causal-conv "causal-conv.lisp")

(defun shortconv-layer (weights l slot)
  ;; The layer-L :shortconv entry of the table. SLOT is its index into the
  ;; recurrent-state vector make-state builds.
  (list :kind :shortconv
        :slot slot
        :norm (aref (getf weights :rms-att) l)
        :win (aref (getf weights :conv-in) l)
        :w (aref (getf weights :conv-w) l)
        :wout (aref (getf weights :conv-out) l)))

(defun shortconv-state (layer)
  ;; The previous L-1 gated inputs, oldest row first, and the per-token scratch.
  (let* ((w (getf layer :w)) (dim (array-dimension w 0)))
    (list :window (conv-window w)
          :h (vec:zeros dim :element-type 'single-float)
          :c (vec:zeros dim :element-type 'single-float)
          :y (vec:zeros dim :element-type 'single-float))))

(defun shortconv-forward (layer st xb)
  ;; The whole mixer over the normed input XB; returns what the residual adds.
  (let* ((bcx (vec:matvec (getf layer :win) xb))
         (h (getf st :h))
         (c (getf st :c))
         (y (getf st :y))
         (dim (length h))
         (cb dim)
         (xb2 (* 2 dim)))
    (dotimes (i dim) (setf (aref h i) (* (aref bcx i) (aref bcx (+ xb2 i)))))
    (causal-conv (getf st :window) (getf layer :w) h c)
    (dotimes (i dim) (setf (aref y i) (* (aref bcx (+ cb i)) (aref c i))))
    (vec:matvec (getf layer :wout) y)))
