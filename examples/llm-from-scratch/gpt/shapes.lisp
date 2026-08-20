;; gpt/shapes.lisp -- the shape check of the gpt package, in the spirit of
;; attention.py's own `if __name__ == "__main__"` block (transformer/shapes.lisp).
;;
;; Everything printed here is a SHAPE, a count or an exact token id, so the
;; output is one text on every backend regardless of the low-order float digits.
;;
;;   rontolisp gpt/shapes.lisp

(load "trainer.lisp")

(linalg:seed 42)

(defparameter *corpus* "the quick brown fox jumps over the lazy dog. ")

(defparameter *tokenizer* (simple-tokenizer *corpus*))

(format t "vocabulary:                   ~a characters~%"
        (tokenizer-vocab-size *tokenizer*))
(format t "encode/decode round trip:     ~a~%"
        (string=
         (tokenizer-decode *tokenizer* (tokenizer-encode *tokenizer* *corpus*))
         *corpus*))

(defparameter *config*
  (gpt-config :vocab-size (tokenizer-vocab-size *tokenizer*)
              :n-embd 16
              :n-layer 2
              :n-head 2
              :block-size 8
              :dropout 0.1))

(format t "configured model size:        ~,4f M parameters~%"
        (torch:forward *config*))

(defparameter *model* (gpt-from-config *config*))

(format t "model parameters:             ~a tensors~%"
        (length (torch:parameters *model*)))

(defparameter *groups* (gpt-parameter-groups *model*))

(format t "decay / no-decay split:       ~a / ~a tensors~%"
        (length (car *groups*)) (length (cadr *groups*)))

(defparameter *loaders*
  (create-dataloaders *corpus* *tokenizer*
                      :block-size 8
                      :batch-size 4
                      :train-split 0.75))

(defparameter *train-loader* (car *loaders*))

(defparameter *batch*
  (data-loader-collate *train-loader*
                       (car (data-loader-batches *train-loader*))))

(format t "batch inputs / targets:       ~a / ~a~%" (torch:shape (car *batch*))
        (torch:shape (cadr *batch*)))
(format t "gpt logits:                   ~a~%"
        (torch:shape (gpt-forward *model* (car *batch*))))
(format t "loss is a scalar:             ~a~%"
        (null (torch:shape (gpt-loss *model* (car *batch*) (cadr *batch*)))))

;; The causal mask is what makes this a decoder: position i may not see j > i,
;; so the logits at position 0 cannot change when a LATER token is replaced.
(torch:eval *model*)

(defun first-position-logits (tokens)
  (linalg:to-list
   (linalg:flatten
    (linalg:slice
     (torch:data (gpt-forward *model* (torch:pad-sequence (list tokens))))
     '((0 1) (0 1))))))

(format t "causal mask holds:            ~a~%"
 (equal (first-position-logits '(1 2 3 4)) (first-position-logits '(1 2 3 9))))

(format t "generated length:             ~a tokens~%"
        (length
         (gpt-generate *model* (tokenizer-encode *tokenizer* "the") 6
                       :temperature 0.8
                       :top-k 4)))

;; With :top-k 1 only the single best token can be drawn, so sampling becomes
;; greedy decoding and two runs from the same prompt agree exactly.
(format t "top-k 1 is deterministic:     ~a~%"
        (equal
         (gpt-generate *model* (tokenizer-encode *tokenizer* "the") 8 :top-k 1)
         (gpt-generate *model* (tokenizer-encode *tokenizer* "the") 8
                       :top-k 1)))
