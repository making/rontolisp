;; transformer/attention.lisp -- llm_from_scratch/transformer/attention.py,
;; ported.
;;
;; Dot-product attention, its scaled and masked form, one attention head and
;; multi-head attention. torch.bmm is `torch:matmul' (rank >= 3 is the stacked
;; product), nn.Module is `torch:module' plus a forward defun, and nn.ModuleList
;; is a plain LIST in a field -- torch:parameters recurses into it, so every
;; head's weights reach the optimizer.
;;
;; Loaded by transformer.lisp and by the chapter02 sections.

(defparameter *neg-infinity* (/ -1.0 0.0))

(defun dot-product-attention (query key value)
  ;; softmax(Q K^T) V over a (batch length d-model) batch: the scores are
  ;; (batch query-length key-length) and each query row's weights sum to 1.
  (torch:matmul
   (torch:softmax (torch:matmul query (torch:transpose key '(0 2 1))) :axis -1)
   value))

(defun scaled-dot-product-attention (query key value &optional mask)
  ;; The same with the 1/sqrt(d_k) scale, and an optional mask: every NON-ZERO
  ;; position is filled with -infinity before the softmax, so its weight comes
  ;; out exactly 0. The mask is a raw linalg array broadcasting over
  ;; (batch query-length key-length).
  (let* ((d-k (car (last (torch:shape query))))
         (score
          (torch:div (torch:matmul query (torch:transpose key '(0 2 1)))
                     (sqrt (* 1.0 d-k))))
         (masked
          (if (null mask) score (torch:masked-fill score mask *neg-infinity*))))
    (torch:matmul (torch:softmax masked :axis -1) value)))

(defun attention-head-forward (self query key value &optional mask)
  (scaled-dot-product-attention
   (torch:forward (torch:field self :linear-q) query)
   (torch:forward (torch:field self :linear-k) key)
   (torch:forward (torch:field self :linear-v) value) mask))

(defun attention-head (d-k d-v d-model)
  ;; One head: three projections of the (batch length d-model) input into the
  ;; head's own subspace, then scaled dot-product attention over them.
  (torch:module :attention-head (list :linear-q (torch:linear d-model d-k)
                                      :linear-k (torch:linear d-model d-k)
                                      :linear-v (torch:linear d-model d-v))
                (function attention-head-forward)))

(defun multi-head-attention-forward (self query key value &optional mask)
  (torch:forward (torch:field self :linear-o)
                 (torch:cat (mapcar (lambda (head)
                                      (torch:forward head query key value mask))
                                    (torch:field self :heads))
                            :axis -1)))

(defun multi-head-attention (n-heads d-k d-v d-model)
  ;; n-heads independent heads concatenated along the feature axis and mixed by
  ;; one more projection back to d-model.
  (let ((heads nil))
    (dotimes (i n-heads)
      (setq heads (cons (attention-head d-k d-v d-model) heads)))
    (torch:module :multi-head-attention (list :heads (reverse heads)
                                              :linear-o
                                              (torch:linear (* n-heads d-v)
                                                            d-model))
                  (function multi-head-attention-forward))))
