;; The book's shapes with a corpus small enough to measure a STEP: the chapter-3 GPT at
;; `d_model` 384, block 256, 6 layers, 6 heads, batch 64 -- the configuration `.kb/gpu.md`
;; calls "the book's shapes" -- over a SYNTHETIC corpus of 36456 characters and 3038
;; distinct ones, the vocabulary of the full novel. The novel itself costs six minutes of
;; data-loader setup a run and the step does not depend on the corpus, so this file is
;; what the per-step rows are taken on: run it at two step counts and diff.
;;
;;   STEPS=3  java -jar target/...-exec.jar .todo/123-gpu-acceleration/gpt-book-shapes-fast.lisp
;;   STEPS=13 ...
;;   the step is (t13 - t3) / 10; BATCH=32 halves the batch where the graph does not fit
;;
;; Not project code: a probe, like everything else in this directory.

(load "../../examples/llm-from-scratch/gpt/trainer.lisp")

(linalg:seed 42)

;; --- the corpus --------------------------------------------------------------
;; Each of the 3038 characters appears exactly twelve times, in an order that is a
;; multiplier's orbit rather than a run: 1237 is coprime with 3038, so `(* i 1237)`
;; mod 3038 walks the whole vocabulary before it repeats.

(defparameter *vocab-size* 3038)

(defparameter *corpus-size* 36456)

(defparameter *text*
  (let ((out (make-string *corpus-size*)))
    (dotimes (i *corpus-size* out)
      (setf (char out i)
            (code-char (+ #x4e00 (mod (* i 1237) *vocab-size*)))))))

;; --- the shapes --------------------------------------------------------------

(defparameter *block-size* 256)

(defparameter *n-embd* 384)

(defparameter *n-layer* 6)

(defparameter *n-head* 6)

(defparameter *dropout* 0.1)

(defparameter *batch-size*
  ;; BATCH overrides the book's 64 where the machine cannot hold the step's graph
  ;; (todo-499 was measured at 32 beside a 93 GB LLM server; the kernels are
  ;; bandwidth-bound, so a step scales with it).
  (let ((batch (uiop:getenv "BATCH"))) (if batch (parse-integer batch) 64)))

(defparameter *max-steps*
  (let ((steps (uiop:getenv "STEPS"))) (if steps (parse-integer steps) 13)))

(defparameter *warmup-steps* 8)

(defparameter *learning-rate* 0.04)

(format t "corpus size: ~a characters~%" (length *text*))

;; --- the tokenizer -----------------------------------------------------------

(defparameter *tokenizer* (simple-tokenizer *text*))

(format t "vocabulary: ~a distinct characters~%"
        (tokenizer-vocab-size *tokenizer*))

;; --- the data ----------------------------------------------------------------

(defparameter *loaders*
  (create-dataloaders *text* *tokenizer*
                      :block-size *block-size*
                      :batch-size *batch-size*
                      :train-split 0.9))

(defparameter *train-loader* (car *loaders*))

(defparameter *val-loader* (cadr *loaders*))

(format t "train batches: ~a | validation batches: ~a~%"
        (length (data-loader-batches *train-loader*))
        (length (data-loader-batches *val-loader*)))

;; --- the model ---------------------------------------------------------------

(defparameter *config*
  (gpt-config :vocab-size (tokenizer-vocab-size *tokenizer*)
              :n-embd *n-embd*
              :n-layer *n-layer*
              :n-head *n-head*
              :block-size *block-size*
              :dropout *dropout*))

(format t "configured size: ~,4f M parameters~%" (torch:forward *config*))

(defparameter *model* (gpt-from-config *config*))

;; --- training ----------------------------------------------------------------
;; No evaluation pass and no sampling: the step is what is being measured.

(defparameter *trainer*
  (gpt-trainer *model* *train-loader* *val-loader*
               :learning-rate *learning-rate*
               :weight-decay 0.1
               :warmup-steps *warmup-steps*
               :max-steps *max-steps*
               :grad-clip 1.0))

(defparameter *losses*
  (gpt-trainer-train *trainer* :log-interval 1 :eval-interval 1000000))

(defparameter *train-losses* (car *losses*))

(format t "steps: ~a | first loss ~,4f -> last loss ~,4f~%" *max-steps*
        (car *train-losses*) (car (last *train-losses*)))
