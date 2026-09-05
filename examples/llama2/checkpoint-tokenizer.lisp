;;;; The tokenizer a published checkpoint carries, read into the shipped
;;;; tokenizer: package -- from a Hugging Face checkpoint's tokenizer.json +
;;;; tokenizer_config.json (load-hf-tokenizer), or from a GGUF's tokenizer.*
;;;; metadata (load-gguf-tokenizer). llama2.lisp loads this file; so does
;;;; checkpoint-tokenizer-check.lisp, which pins the ids against the Python
;;;; `tokenizers` library over the fixture tokenizer-fixture.py writes.
;;;;
;;;; What the two readers agree on, because the reference implementation does:
;;;; EVERY added token is matched whole before byte-level BPE runs -- the ones
;;;; flagged "special" (<|im_start|>, <|im_end|>) and the ones not (Qwen3's
;;;; <think> / </think> / <tool_call>); "special" only governs whether a
;;;; decoder skips the token. A GGUF spells the same two classes as token
;;;; types 3 (control) and 4 (user-defined), and llama.cpp matches both whole.
;;;; The pre-tokenizer kind and the BOS rule are read off tokenizer.json itself
;;;; rather than assumed from the model family (hf-tokenizer-kind,
;;;; hf-adds-bos-p).

(defun read-text-file (path)
  ;; The whole UTF-8 text of a file, read as bytes in 1 MB chunks (file-length
  ;; is nil on WASM) and decoded once -- tokenizer.json is 13 MB, and a
  ;; line-by-line concatenation of it is quadratic.
  (with-open-file (s path :element-type '(unsigned-byte 8))
    (let ((chunks '())
          (total 0)
          (buf (make-array 1048576 :element-type '(unsigned-byte 8))))
      (loop
        (let ((n (read-sequence buf s)))
          (when (= n 0) (return))
          (push (subseq buf 0 n) chunks)
          (setq total (+ total n))
          (when (< n 1048576) (return))))
      (let ((bytes (make-array total :element-type '(unsigned-byte 8))) (at 0))
        (dolist (chunk (nreverse chunks))
          (replace bytes chunk :start1 at)
          (setq at (+ at (length chunk))))
        (rontolisp:octets-to-string bytes)))))

(defun json-parse-file (path) (rontolisp:json-parse (read-text-file path)))

(defun token-name (v)
  ;; tokenizer_config.json spells a special token as a string or as an
  ;; object with a "content"; absent or null is nil.
  (cond ((stringp v) v) ((hash-table-p v) (gethash "content" v)) (t nil)))

(defun json-null-p (v) (or (null v) (eq v 'null)))

(defun split-regex-kind (regex fallback)
  ;; A Split pre-tokenizer's regex -> the kind whose scanner it is, told apart
  ;; by the one clause that differs (tokenizers.lisp lists them): the number
  ;; run `\p{N}{1,3}` is Llama 3's, a lone `\p{N}` Qwen 2.5 / 3's, and the
  ;; word class `[\p{L}\p{M}]+` Qwen 3.5's. FALLBACK for a regex none of
  ;; those matches -- the architecture row's kind, or nil.
  (cond ((search "[\\p{L}\\p{M}]+" regex) :qwen35)
        ((search "\\p{N}{1,3}" regex) :llama3)
        ((search "|\\p{N}|" regex) :qwen2)
        (t fallback)))

(defun hf-tokenizer-kind (json fallback)
  ;; The pre-tokenizer kind a tokenizer.json describes, read off its
  ;; pre_tokenizer block rather than assumed from the family: a Digits step in
  ;; front of ByteLevel is :smollm, ByteLevel with its own regex :gpt2, a Split
  ;; regex whichever scanner it spells. Nil when the file is not byte-level BPE
  ;; at all -- TinyLlama's is SentencePiece under the same "BPE" model type,
  ;; with no ByteLevel step -- which sends the caller to tokenizer.bin.
  (let ((pre (gethash "pre_tokenizer" json)))
    (if (json-null-p pre)
        nil
        (let ((steps
               (if (string= (gethash "type" pre) "Sequence")
                   (coerce (gethash "pretokenizers" pre) 'list)
                   (list pre)))
              (digits nil)
              (byte-level nil)
              (own-regex nil)
              (split nil))
          (dolist (step steps)
            (let ((type (gethash "type" step)))
              (cond ((string= type "Digits") (setq digits t))
                    ((string= type "ByteLevel")
                     (setq byte-level t)
                     (when (gethash "use_regex" step) (setq own-regex t)))
                    ((string= type "Split")
                     (setq split (gethash "Regex" (gethash "pattern" step)))))))
          (cond ((not byte-level) nil)
                (split (split-regex-kind split fallback))
                ((and own-regex digits) :smollm)
                (own-regex :gpt2)
                (t fallback))))))

(defun template-opens-with-special-p (processor)
  ;; Whether a post_processor (or one of a Sequence's) is a TemplateProcessing
  ;; whose single-sequence template starts with a special token.
  (let ((type (gethash "type" processor)))
    (cond ((string= type "Sequence")
           (some #'template-opens-with-special-p
                 (coerce (gethash "processors" processor) 'list)))
          ((string= type "TemplateProcessing")
           (let ((single (gethash "single" processor)))
             (and (> (length single) 0)
              (not (json-null-p (gethash "SpecialToken" (aref single 0)))))))
          (t nil))))

(defun hf-adds-bos-p (json config)
  ;; Whether encoding prepends BOS. tokenizer_config.json's add_bos_token says
  ;; so when it is there; otherwise it is the post_processor's business -- a
  ;; TemplateProcessing opening with a special token (Llama 3, LFM2). Qwen's
  ;; ByteLevel post-processor and SmolLM2's null add none, whatever bos_token
  ;; the config names (SmolLM2 names <|im_start|>, and prepending it would put
  ;; a second one in front of every chat turn).
  (multiple-value-bind (v present) (gethash "add_bos_token" config)
    (if (and present (not (json-null-p v)))
        v
        (let ((post (gethash "post_processor" json)))
          (and (not (json-null-p post))
               (template-opens-with-special-p post))))))

(defun load-hf-tokenizer (dir fallback-kind)
  ;; tokenizer.json (the vocab, the ranked merges, the added tokens) and
  ;; tokenizer_config.json (which added tokens are BOS and EOS) -> a byte-level
  ;; BPE tokenizer of the pre-tokenizer kind the file describes (FALLBACK-KIND,
  ;; the architecture row's, when it describes one this file cannot name), or
  ;; nil when the file is not byte-level BPE.
  (let* ((json (json-parse-file (concatenate 'string dir "tokenizer.json")))
         (kind (hf-tokenizer-kind json fallback-kind)))
    (and kind (load-hf-bpe-tokenizer dir json kind))))

(defun load-hf-bpe-tokenizer (dir json kind)
  (let* ((config
          (json-parse-file (concatenate 'string dir "tokenizer_config.json")))
         (vocab (gethash "vocab" (gethash "model" json)))
         (merges (gethash "merges" (gethash "model" json)))
         (added (gethash "added_tokens" json))
         (n 0))
    (maphash (lambda (k id) (when (>= id n) (setq n (+ id 1)))) vocab)
    (dotimes (i (length added))
      (let ((id (gethash "id" (aref added i))))
        (when (>= id n) (setq n (+ id 1)))))
    (let ((tokens (make-array n :initial-element "")) (specials '()))
      (maphash (lambda (k id) (setf (aref tokens id) k)) vocab)
      ;; every added token is matched whole, whatever its "special" flag says
      ;; (the flag only tells a decoder what to skip): Qwen3 ships <think>,
      ;; </think> and <tool_call> unflagged, and a chat prompt that spells its
      ;; think block as three pieces is a different prompt
      (dotimes (i (length added))
        (let ((a (aref added i)))
          (setf (aref tokens (gethash "id" a)) (gethash "content" a))
          (push (gethash "content" a) specials)))
      (let ((bos
             (and (hf-adds-bos-p json config)
                  (token-name (gethash "bos_token" config))))
            (eos (token-name (gethash "eos_token" config))))
        (tokenizer:make-bpe tokens merges
         :kind kind
         :specials specials
         :bos (and bos (position bos tokens :test #'string=))
         :eos (and eos (position eos tokens :test #'string=)))))))

(defun load-gguf-tokenizer (f add-bos)
  ;; The tokenizer a GGUF carries -- F its gguf:tokenizer-fields, ADD-BOS its
  ;; tokenizer.ggml.add_bos_token: byte-level BPE (model "gpt2") or
  ;; SentencePiece ("llama"), each in the shape the tokenizer package takes.
  ;; A BOS the file says not to add (Qwen) is left out of the tokenizer.
  (let* ((bos (and add-bos (getf f :bos))))
    (if (string= (getf f :model) "llama")
        (tokenizer:make-sentencepiece (getf f :tokens) (getf f :scores)
                                      :bos bos
                                      :eos (getf f :eos))
        (let ((specials '())
              (tokens (getf f :tokens))
              (types (getf f :token-type)))
          ;; token type 3 = control and 4 = user-defined: both are matched
          ;; whole, as llama.cpp matches them (a converter writes Qwen3's
          ;; unflagged <think> as type 4)
          (when types
            (dotimes (i (length types))
              (let ((type (aref types i)))
                (when (or (= type 3) (= type 4))
                  (push (aref tokens i) specials)))))
          (tokenizer:make-bpe tokens (getf f :merges)
                              :kind (getf f :pre)
                              :specials specials
                              :bos bos
                              :eos (getf f :eos))))))
