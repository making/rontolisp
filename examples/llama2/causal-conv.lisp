;;;; The causal depthwise convolution the hybrid mixers share: Qwen3.5's Gated
;;;; DeltaNet runs one of kernel 4 over its q|k|v projection (deltanet.lisp),
;;;; LFM2's short-conv layer one of kernel 3 over its gated input
;;;; (shortconv.lisp). Both are transformers' causal_conv1d_update, the
;;;; single-token path: the previous kernel-1 inputs are the layer's state, and
;;;; a token's output is the kernel's taps over that window and the new input.
;;;; Loaded once by whichever of the two files comes first (require/provide).
(provide :causal-conv)

(defun causal-conv (window w x out)
  ;; One token of a causal depthwise convolution: out[c] = sum over the taps
  ;; of w[c][tap] * that tap's input, the last tap being the current X and the
  ;; earlier ones the WINDOW rows (oldest first). W is channels x kernel, the
  ;; window (kernel-1) x channels. The window then shifts by one row and takes
  ;; X as its newest. No activation: the caller applies its own, if any.
  (let ((n (length x)) (m (array-dimension window 0)))
    (dotimes (c n)
      (let ((acc (* (aref w c m) (aref x c))))
        (dotimes (r m) (setq acc (+ acc (* (aref w c r) (aref window r c)))))
        (setf (aref out c) acc)))
    (dotimes (r (- m 1))
      (dotimes (c n) (setf (aref window r c) (aref window (+ r 1) c))))
    (dotimes (c n) (setf (aref window (- m 1) c) (aref x c)))))

(defun conv-window (w)
  ;; The zero state a layer with kernel W (channels x kernel) starts from.
  (linalg:zeros (list (- (array-dimension w 1) 1) (array-dimension w 0))
                :element-type 'single-float))
