;;;; The chapter-2 Transformer at the notebook's shapes, with a corpus small enough to
;;;; measure a STEP: d_model 512, 6 blocks, 8 heads, d_k/d_v 64, d_ff 512, batch 64,
;;;; max_length 20, a 6638-token vocabulary -- the configuration `.todo/650` was profiled
;;;; at. The shipped `examples/llm-from-scratch/chapter02/section5.lisp` is a 1-block,
;;;; 2-head toy over eight sentence pairs, which is the right size for an example and the
;;;; wrong one for a measurement.
;;;;
;;;; The corpus is SYNTHETIC ids rather than a tokenized parallel corpus: the step does not
;;;; depend on what the tokens mean, and the sentence LENGTHS are what matters here --
;;;; they vary from 10 to 19 tokens with the wrappers so that `torch:padding-mask` has real work and the batch is
;;;; genuinely ragged.
;;;;
;;;;   STEPS=3  java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED Tf
;;;;   STEPS=13 ...
;;;;   the step is (t13 - t3) / 10
;;;;
;;;; WIDEN=1 materializes the source mask at the score's own shape, so every head's fused
;;;; softmax is ACCEPTED: the A/B that prices the 96 shape declines a step (todo-650, and
;;;; `.kb/gpu.md`, "What the fold's SHAPE decline costs on this backend").
;;;;
;;;; Not project code: a probe, like everything else in this directory.

(load "../../examples/llm-from-scratch/transformer/transformer.lisp")

(linalg:seed 42)

(defparameter *pad-id* 0)

(defparameter *bos-id* 1)

(defparameter *eos-id* 2)

(defparameter *vocab-size* 6638)

(defparameter *d-model* 512)

(defparameter *n-blocks* 6)

(defparameter *n-heads* 8)

(defparameter *d-k* 64)

(defparameter *d-v* 64)

(defparameter *d-ff* 512)

(defparameter *batch-size* 64)

(defparameter *max-length* 20)

(defparameter *learning-rate* 0.0001)

(defparameter *sentences* 256)

(defun synthetic-ids (i)
  ;; A sentence of 8 to 17 content tokens, wrapped in <bos> and <eos>: 1237 is coprime
  ;; with the vocabulary, so the multiplier's orbit walks it rather than running.
  (let ((n (+ 8 (mod (* i 7) 10))) (out nil))
    (dotimes (k n)
      (push (+ 3 (mod (* (+ (* i 31) k) 1237) (- *vocab-size* 3))) out))
    (append (list *bos-id*) (reverse out) (list *eos-id*))))

(defparameter *source-ids*
  (let ((out nil))
    (dotimes (i *sentences* (reverse out)) (push (synthetic-ids i) out))))

(defparameter *target-ids*
  (let ((out nil))
    (dotimes (i *sentences* (reverse out)) (push (synthetic-ids (+ i 1)) out))))

(defparameter *model*
  (transformer *vocab-size* *vocab-size* *max-length* *d-model* *n-blocks* *n-heads*
               *d-k* *d-v* *d-ff*))

(defparameter *optimizer* (torch:adam *model* :lr *learning-rate*))

(format t "model parameters: ~a tensors~%" (length (torch:parameters *model*)))

(defun batch-of (all indices) (mapcar (lambda (i) (nth i all)) indices))

(defun train-step (indices)
  ;; section5.lisp's train-step verbatim: the decoder reads the target without its last
  ;; token and is scored against the target without its first, under a PADDING mask on the
  ;; source -- (batch 1 length), the shape `LinalgGpu.suffixLength` refuses -- and a
  ;; padding + subsequent mask on the target, which is (batch length length) and does not.
  (let* ((source (torch:pad-sequence (batch-of *source-ids* indices)
                                     :padding-value *pad-id*))
         (target (torch:pad-sequence (batch-of *target-ids* indices)
                                     :padding-value *pad-id*))
         (length (cadr (torch:shape target)))
         (target-input (torch:slice target (list nil (list 0 (- length 1)))))
         (target-output (torch:slice target (list nil (list 1 length))))
         (source-mask (torch:padding-mask source :pad-id *pad-id*))
         (target-mask
          (linalg:add (torch:padding-mask target-input :pad-id *pad-id*)
                      (torch:subsequent-mask (- length 1))))
         ;; WIDEN=1 materializes the source mask at the SCORE's own shape, which is what
         ;; `LinalgGpu.suffixLength` / `JvmGpuTemplate.softmaxMaskLength` would accept --
         ;; the price of the fix that changes the mask rather than the rule. Two of them,
         ;; because the encoder's query axis is the source length and the cross
         ;; attention's is the target's.
         (source-length (cadr (torch:shape source)))
         (self-mask
          (if *widen*
              (linalg:add source-mask
                          (linalg:zeros (list *batch-size* source-length source-length)))
              source-mask))
         (cross-mask
          (if *widen*
              (linalg:add source-mask
                          (linalg:zeros
                           (list *batch-size* (- length 1) source-length)))
              source-mask))
         (logits
          (torch:forward *model* source target-input self-mask target-mask cross-mask))
         (loss
          (torch:cross-entropy-loss logits target-output :ignore-index *pad-id*)))
    (torch:zero-grad *optimizer*)
    (torch:backward loss)
    (torch:step *optimizer*)
    (torch:item loss)))

(defparameter *steps*
  (let ((env (uiop:getenv "STEPS"))) (if env (parse-integer env) 3)))

(defparameter *widen*
  ;; WIDEN=1 gives the source mask the score's own shape, so every head's fused softmax is
  ;; ACCEPTED and the 96 shape declines a step disappear -- the A against the B.
  (equal "1" (uiop:getenv "WIDEN")))

(format t "steps: ~a | batch ~a | d_model ~a | ~a blocks x ~a heads | widen ~a~%" *steps*
        *batch-size* *d-model* *n-blocks* *n-heads* *widen*)

(dotimes (step *steps*)
  (let* ((base (mod (* step *batch-size*) (- *sentences* *batch-size*)))
         (indices
          (let ((out nil))
            (dotimes (k *batch-size* (reverse out)) (push (+ base k) out)))))
    (format t "step ~a/~a | loss ~,4f~%" step *steps* (train-step indices))))
