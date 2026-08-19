;; chapter02/section5.lisp -- notebook section 2.5, ported.
;;
;; The whole chapter, end to end: the cross entropy a language model is trained
;; with (2.5.1), the padding and subsequent masks (2.5.2), and a Transformer
;; trained on a Japanese-English corpus and then decoded greedily (2.5.3).
;;
;; The notebook clones odashi/small_parallel_enja and trains a d_model=512,
;; 6-block, 8-head model for 20 epochs on a GPU. This port ships its own corpus
;; -- eight pairs, right here in the file, so the example is hermetic -- and a
;; model small enough to finish on the plain interpreter. It MEMORISES that
;; corpus; it does not generalise, and it is not meant to. The parameters at the
;; top are the knobs: raise them (and add data) to walk toward the book's run.
;;
;;   rontolisp chapter02/section5.lisp

(load "../transformer/transformer.lisp")

;; --- 2.5.1: cross entropy over a probability target -------------------------
;; The notebook feeds nn.CrossEntropyLoss a one-hot "logit" vector and two
;; probability targets. The loss is lower for the target that agrees with where
;; the model puts its mass.

(defparameter *logits* (torch:tensor '(1.0 0.0 0.0)))

(format t "cross entropy against (0.7 0.2 0.1): ~,4f~%"
 (torch:item (torch:cross-entropy-loss *logits* (torch:tensor '(0.7 0.2 0.1)))))
(format t "cross entropy against (0.1 0.2 0.7): ~,4f~%"
 (torch:item (torch:cross-entropy-loss *logits* (torch:tensor '(0.1 0.2 0.7)))))

;; --- 2.5.2: the two masks ----------------------------------------------------

(defun print-mask (label mask rows columns)
  (format t "~a ~a~%" label (linalg:shape mask))
  (dotimes (i rows)
    (format t " ")
    (dotimes (j columns) (format t " ~a" (truncate (aref mask 0 i j))))
    (format t "~%")))

(defparameter *sample-tokens*
  (torch:pad-sequence '((5 3 3) (1 9 4 3 1) (5 3 5 1))))

(defparameter *sample-padding-mask*
  (torch:padding-mask *sample-tokens* :pad-id 0))

(format t "tokens:  ~a~%"
        (mapcar (function truncate)
                (linalg:to-list (linalg:row (torch:data *sample-tokens*) 0))))
(print-mask "padding mask" *sample-padding-mask* 1 5)
(print-mask "subsequent mask" (torch:subsequent-mask 5) 5 5)

;; A masked score is filled with -infinity, so its softmax weight is exactly 0.
(defparameter *scores* (torch:tensor (linalg:zeros '(1 5 5))))

(defparameter *masked-weights*
  (torch:softmax
   (torch:masked-fill *scores* (torch:subsequent-mask 5) *neg-infinity*)
   :axis -1))

(format t "causal attention weights:~%")
(dotimes (i 5)
  (format t " ")
  (dotimes (j 5) (format t " ~,2f" (aref (torch:data *masked-weights*) 0 i j)))
  (format t "~%"))

;; --- 2.5.3: the corpus -------------------------------------------------------

(defparameter *corpus*
  '(("今日 の 天気 は 晴れ です 。" "the weather is fine today .")
    ("明日 の 天気 は 雨 です 。" "the weather is rainy tomorrow .")
    ("今日 は 寒い です 。" "it is cold today .") ("私 は 猫 が 好き です 。" "i like cats .")
    ("私 は 犬 が 好き です 。" "i like dogs .") ("彼 は 本 を 読み ます 。" "he reads a book .")
    ("私 は 学生 です 。" "i am a student .") ("猫 が 走り ます 。" "the cat runs .")))

(defparameter *specials* '("<unk>" "<pad>" "<bos>" "<eos>"))

(defparameter *unk-id* 0)

(defparameter *pad-id* 1)

(defparameter *bos-id* 2)

(defparameter *eos-id* 3)

(defun split-tokens (line)
  ;; The book's line.split(): whitespace-separated tokens, as a list of strings.
  (let ((tokens nil) (current nil))
    (dotimes (i (length line))
      (let ((ch (char line i)))
        (if (char= ch #\Space)
            (when current
              (setq tokens (cons (coerce (reverse current) 'string) tokens))
              (setq current nil))
            (setq current (cons ch current)))))
    (when current
      (setq tokens (cons (coerce (reverse current) 'string) tokens)))
    (reverse tokens)))

(defun wrap-sentence (line)
  ;; iter_corpus(): the token list with <bos> in front and <eos> behind.
  (append (list "<bos>") (split-tokens line) (list "<eos>")))

(defun build-vocabulary (sentences)
  ;; build_vocab_from_iterator(): the specials first, then every other token by
  ;; DESCENDING frequency, ties keeping first appearance -- Counter.most_common.
  ;; Returns (token-to-id id-to-token).
  (let ((counts (make-hash-table :test (function equal)))
        (seen nil)
        (highest 0))
    (dolist (tokens sentences)
      (dolist (token tokens)
        (if (gethash token counts)
            (setf (gethash token counts) (+ 1 (gethash token counts)))
            (progn
              (setf (gethash token counts) 1)
              (setq seen (cons token seen))))))
    (setq seen (reverse seen))
    (dolist (token seen)
      (when (> (gethash token counts) highest)
        (setq highest (gethash token counts))))
    (let ((ordered nil))
      (do ((c highest (- c 1)))
          ((< c 1))
        (dolist (token seen)
          (when (= (gethash token counts) c)
            (setq ordered (cons token ordered)))))
      (let ((token-to-id (make-hash-table :test (function equal)))
            (id-to-token nil)
            (next 0))
        (dolist (token (append *specials* (reverse ordered)))
          (unless (gethash token token-to-id)
            (setf (gethash token token-to-id) next)
            (setq id-to-token (cons token id-to-token))
            (setq next (+ next 1))))
        (list token-to-id (reverse id-to-token))))))

(defun tokens-to-ids (tokens token-to-id)
  ;; Vocab.__getitem__ with default_index = <unk>.
  (mapcar (lambda (token)
            (let ((id (gethash token token-to-id))) (if (null id) *unk-id* id)))
          tokens))

(defun ids-to-text (ids id-to-token)
  (let ((out ""))
    (dolist (id ids out)
      (setq out
            (if (string= out "")
                (nth id id-to-token)
                (concatenate 'string out " " (nth id id-to-token)))))))

(defparameter *source-sentences*
  (mapcar (lambda (pair) (wrap-sentence (car pair))) *corpus*))

(defparameter *target-sentences*
  (mapcar (lambda (pair) (wrap-sentence (cadr pair))) *corpus*))

(defparameter *source-vocabulary* (build-vocabulary *source-sentences*))

(defparameter *target-vocabulary* (build-vocabulary *target-sentences*))

(defparameter *source-ids*
  (mapcar (lambda (tokens) (tokens-to-ids tokens (car *source-vocabulary*)))
          *source-sentences*))

(defparameter *target-ids*
  (mapcar (lambda (tokens) (tokens-to-ids tokens (car *target-vocabulary*)))
          *target-sentences*))

(format t "source vocabulary: ~a tokens~%" (length (cadr *source-vocabulary*)))
(format t "target vocabulary: ~a tokens~%" (length (cadr *target-vocabulary*)))
(format t "<unk> id: ~a, <pad> id: ~a, <bos> id: ~a, <eos> id: ~a~%" *unk-id*
        *pad-id* *bos-id* *eos-id*)
(format t "first source sentence: ~a~%" (car *source-ids*))
(format t "first target sentence: ~a~%" (car *target-ids*))

;; --- 2.5.3: training ---------------------------------------------------------

(defparameter *d-model* 8)

(defparameter *n-blocks* 1)

(defparameter *n-heads* 2)

(defparameter *d-k* 4)

(defparameter *d-v* 4)

(defparameter *d-ff* 16)

(defparameter *batch-size* 4)

(defparameter *epochs* 40)

(defparameter *learning-rate* 0.02)

(defparameter *max-length* 12)

(linalg:seed 7)

(defparameter *model*
  (transformer (length (cadr *source-vocabulary*))
               (length (cadr *target-vocabulary*)) *max-length* *d-model*
               *n-blocks* *n-heads* *d-k* *d-v* *d-ff*))

(defparameter *optimizer* (torch:adam *model* :lr *learning-rate*))

(format t "model parameters:  ~a tensors~%" (length (torch:parameters *model*)))

(defun batch-of (all indices) (mapcar (lambda (i) (nth i all)) indices))

(defun train-step (indices)
  ;; One optimizer step over a mini-batch, exactly the notebook's train() body:
  ;; the decoder reads the target without its last token and is scored against
  ;; the target without its first, under a padding mask on the source and a
  ;; padding + subsequent mask on the target.
  (let* ((source
          (torch:pad-sequence (batch-of *source-ids* indices)
                              :padding-value *pad-id*))
         (target
          (torch:pad-sequence (batch-of *target-ids* indices)
                              :padding-value *pad-id*))
         (length (cadr (torch:shape target)))
         (target-input (torch:slice target (list nil (list 0 (- length 1)))))
         (target-output (torch:slice target (list nil (list 1 length))))
         (source-mask (torch:padding-mask source :pad-id *pad-id*))
         (target-mask
          (linalg:add (torch:padding-mask target-input :pad-id *pad-id*)
                      (torch:subsequent-mask (- length 1))))
         (logits
          (torch:forward *model* source target-input source-mask target-mask
                         source-mask))
         (loss
          (torch:cross-entropy-loss logits target-output
                                    :ignore-index *pad-id*)))
    (torch:zero-grad *optimizer*)
    (torch:backward loss)
    (torch:step *optimizer*)
    (torch:item loss)))

(format t "training (cross entropy, ignore-index = <pad>):~%")
(dotimes (epoch *epochs*)
  (let ((total 0.0) (steps 0))
    (dolist (batch (torch:shuffled-batches (length *corpus*) *batch-size*))
      (setq total (+ total (train-step batch)))
      (setq steps (+ steps 1)))
    (when (= 0 (mod epoch 5))
      (format t "  epoch ~2,'0d: ~,3f~%" epoch (/ total steps)))))

;; --- 2.5.3: greedy decoding --------------------------------------------------

(torch:eval *model*)

(format t "greedy decode:~%")
(defparameter *correct* 0)

(dotimes (i (length *corpus*))
  (let* ((source
          (torch:pad-sequence (list (nth i *source-ids*))
                              :padding-value *pad-id*))
         (decoded
          (transformer-inference *model* source *bos-id* *eos-id*
                                 :max-length *max-length*))
         (expected (nth i *target-ids*))
         (text (ids-to-text decoded (cadr *target-vocabulary*))))
    (when (equal decoded expected) (setq *correct* (+ 1 *correct*)))
    (format t "  ~a -> ~a~%" (car (nth i *corpus*)) text)))

(format t "sentences reproduced exactly: ~a / ~a~%" *correct* (length *corpus*))
