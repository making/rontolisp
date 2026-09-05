;;;; The tokenizer readers of checkpoint-tokenizer.lisp over the fixture
;;;; tokenizer-fixture.py writes: the same tiny byte-level BPE tokenizer read
;;;; from tokenizer-check/tokenizer.json + tokenizer_config.json and from
;;;; tokenizer-check.gguf, encoding the same three texts. The expected ids are
;;;; the Python `tokenizers` library's (../.expected/checkpoint-tokenizer-check.txt),
;;;; and the texts exercise what a chat prompt does: an added token flagged
;;;; "special" (<|im_start|>, <|im_end|>), one NOT flagged special (<think>,
;;;; </think> -- the way Qwen3 ships its think block; a GGUF calls it a
;;;; user-defined token), and a plain word glued to one. Every added token is
;;;; one id, whatever its flag says.

(load "checkpoint-tokenizer.lisp")

(defparameter *texts*
  (list "Once upon a time"
        (format nil "<|im_start|>user~%hi<|im_end|>~%<think>~%~%</think>~%~%ok")
        "think<think>ok"))

(defun show (label tk)
  (format t "~a: vocabulary ~a bos ~a eos ~a~%" label
          (tokenizer:vocabulary-size tk) (tokenizer:bos-id tk)
          (tokenizer:eos-id tk))
  (dolist (text *texts*) (format t "~a~%" (tokenizer:encode tk text :bos t))))

(show "tokenizer.json" (load-hf-tokenizer "tokenizer-check/" nil))

(let ((m (gguf:read "tokenizer-check.gguf" :metadata-only t)))
  (show "gguf"
        (load-gguf-tokenizer (gguf:tokenizer-fields m)
         (gguf:metadata-value m "tokenizer.ggml.add_bos_token" t))))
