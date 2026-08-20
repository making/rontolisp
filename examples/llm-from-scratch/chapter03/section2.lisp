;; chapter03/section2.lisp -- notebooks/chapter03/section03_tokenizer.py,
;; ported: section 3.2, "Tokenizer".
;;
;; Word-level tokenization and its normalizer (3.2.1), the special tokens and
;; the truncation an input length forces (3.2.2), the two embedding tables
;; (3.2.3), and byte-pair encoding trained on a small corpus (3.2.4).
;;
;; Two things about the port:
;;
;;   * The book's re.findall(r"\w+|[^\w\s]", text) is a SCAN here, not a regexp
;;     call. The pattern is two character classes, which is what a scan is; and
;;     \w is taken as ASCII letter / digit / underscore, which is what this
;;     section's English demo text uses.
;;   * Python's dict preserves INSERTION order, and the BPE loop depends on it:
;;     max(pair_freqs, key=pair_freqs.get) breaks a tie by taking the pair seen
;;     FIRST. Every table below is therefore an ordered association list, and
;;     the tie rule is the strict > in bpe-best-pair -- which is what makes this
;;     port's merges come out in the book's exact order.
;;
;;   rontolisp chapter03/section2.lisp

;; --- 3.2.1 what a tokenizer is ----------------------------------------------

(defun word-char-p (ch)
  ;; Python's \w over ASCII text: a letter, a digit or an underscore.
  (if (or (alphanumericp ch) (char= ch #\_)) t nil))

(defun space-char-p (ch)
  ;; Python's \s, over the whitespace this section's text can hold.
  (if (or (char= ch #\Space) (char= ch #\Tab) (char= ch #\Newline)) t nil))

(defun tokenize-text (text)
  ;; re.findall(r"\w+|[^\w\s]", text): every RUN of word characters is one
  ;; token, every other non-space character is a token on its own, and
  ;; whitespace separates without producing anything.
  (let ((tokens nil) (start nil) (n (length text)))
    (dotimes (i n)
      (let ((ch (char text i)))
        (if (word-char-p ch)
            (when (null start) (setq start i))
            (progn
              (unless (null start)
                (setq tokens (cons (subseq text start i) tokens))
                (setq start nil))
              (unless (space-char-p ch)
                (setq tokens (cons (string ch) tokens)))))))
    (unless (null start) (setq tokens (cons (subseq text start n) tokens)))
    (reverse tokens)))

(defun preprocess-text (text)
  ;; The book's normalizer: lowercase, then a space either side of every
  ;; punctuation character (re.sub(r"([^\w\s])", r" \1 ")).
  (let ((lower (string-downcase text)) (out ""))
    (dotimes (i (length lower) out)
      (let ((ch (char lower i)))
        (setq out
              (if (or (word-char-p ch) (space-char-p ch))
                  (concatenate 'string out (string ch))
                  (concatenate 'string out " " (string ch) " ")))))))

(defun vocabulary-id (vocabulary token)
  ;; The token's id, or nil when the vocabulary does not hold it. The
  ;; vocabulary is an ordered alist of (token . id), which is a dict here.
  (dolist (cell vocabulary nil)
    (when (string= (car cell) token) (return (cdr cell)))))

(defun assign-token-ids (tokens vocabulary)
  ;; Every token's id, with the <UNK> id standing in for anything the
  ;; vocabulary does not hold.
  (let ((unknown (vocabulary-id vocabulary "<UNK>")))
    (mapcar (lambda (token)
              (let ((id (vocabulary-id vocabulary token)))
                (if (null id) unknown id))) tokens)))

(defun token-counts (tokens)
  ;; collections.Counter: (token . count) in FIRST-SEEN order.
  (let ((acc nil))
    (dolist (token tokens (reverse acc))
      (let ((cell
             (dolist (c acc nil) (when (string= (car c) token) (return c)))))
        (if (null cell)
            (setq acc (cons (cons token 1) acc))
            (rplacd cell (+ (cdr cell) 1)))))))

(defun build-vocabulary
    (tokens &key max-size (min-freq 1) (special-tokens '("<PAD>" "<UNK>")))
  ;; The book's build_vocabulary: count, order by DESCENDING frequency, drop
  ;; anything rarer than min-freq, cut to max-size, and number the special
  ;; tokens first. The sort must be STABLE -- Python's sorted() is, so two
  ;; tokens of equal frequency keep the order they were first seen in, and the
  ;; resulting ids are reproducible rather than merely plausible.
  (let* ((counts (token-counts tokens))
         (ordered (stable-sort counts (lambda (x y) (> (cdr x) (cdr y)))))
         (kept nil)
         (n 0))
    (dolist (cell ordered)
      (when (and (>= (cdr cell) min-freq) (or (null max-size) (< n max-size)))
        (setq kept (cons (car cell) kept))
        (setq n (+ n 1))))
    (let ((vocabulary nil) (id 0))
      (dolist (token (append special-tokens (reverse kept)))
        (setq vocabulary (cons (cons token id) vocabulary))
        (setq id (+ id 1)))
      (reverse vocabulary))))

;; --- 3.2.2 what the input length forces -------------------------------------

(defun add-special-tokens (tokens max-length)
  ;; [CLS] before, [SEP] after, then [PAD] up to max-length.
  (let ((out (append (list "[CLS]") tokens (list "[SEP]"))))
    (dotimes (i (- max-length (length out)) out)
      (setq out (append out (list "[PAD]"))))))

(defun truncate-tokens (tokens max-length)
  ;; Longer than max-length: keep the first max-length - 1 and end with [SEP],
  ;; so a truncated sequence still carries its terminator.
  (if (> (length tokens) max-length)
      (append (subseq tokens 0 (- max-length 1)) (list "[SEP]"))
      tokens))

;; --- 3.2.4 byte-pair encoding ------------------------------------------------

(defun split-on-whitespace (text)
  ;; The book's simple_tokenizer: text.split(), i.e. runs of non-space.
  (let ((words nil) (start nil) (n (length text)))
    (dotimes (i n)
      (if (space-char-p (char text i))
          (unless (null start)
            (setq words (cons (subseq text start i) words))
            (setq start nil))
          (when (null start) (setq start i))))
    (unless (null start) (setq words (cons (subseq text start n) words)))
    (reverse words)))

(defun bpe-cell (table key)
  ;; The (key . value) cell of an ordered alist keyed by a STRING.
  (dolist (cell table nil) (when (string= (car cell) key) (return cell))))

(defun bpe-pair-cell (table pair)
  ;; The same, keyed by a PAIR of strings.
  (dolist (cell table nil)
    (when (and (string= (car (car cell)) (car pair))
               (string= (cadr (car cell)) (cadr pair)))
      (return cell))))

(defun bpe-word-freqs (corpus)
  ;; Every whitespace word of the corpus with its count, in first-seen order.
  (let ((acc nil))
    (dolist (text corpus (reverse acc))
      (dolist (word (split-on-whitespace text))
        (let ((cell (bpe-cell acc word)))
          (if (null cell)
              (setq acc (cons (cons word 1) acc))
              (rplacd cell (+ (cdr cell) 1))))))))

(defun bpe-alphabet (word-freqs)
  ;; The base vocabulary: every character occurring in a word, sorted.
  (let ((chars nil))
    (dolist (cell word-freqs)
      (let ((word (car cell)))
        (dotimes (i (length word)) (setq chars (cons (char word i) chars)))))
    (mapcar (function string)
            (sort (remove-duplicates chars) (function char<)))))

(defun bpe-splits (word-freqs)
  ;; Each word as its list of single-character tokens -- the state the merge
  ;; loop rewrites.
  (mapcar (lambda (cell)
            (let ((word (car cell)) (out nil))
              (dotimes (i (length word))
                (setq out (cons (string (char word i)) out)))
              (cons word (reverse out)))) word-freqs))

(defun bpe-pair-freqs (splits word-freqs)
  ;; How often each ADJACENT pair occurs, weighted by its word's count, in
  ;; first-seen order (Python's defaultdict insertion order).
  (let ((acc nil))
    (dolist (wc word-freqs)
      (let ((split (cdr (bpe-cell splits (car wc)))) (freq (cdr wc)))
        (unless (null (cdr split))
          (do ((p split (cdr p)))
              ((null (cdr p)))
            (let* ((pair (list (car p) (cadr p)))
                   (cell (bpe-pair-cell acc pair)))
              (if (null cell)
                  (setq acc (cons (cons pair freq) acc))
                  (rplacd cell (+ (cdr cell) freq))))))))
    (reverse acc)))

(defun bpe-best-pair (pair-freqs)
  ;; max(pair_freqs, key=pair_freqs.get): the most frequent pair, ties going to
  ;; the one seen FIRST -- which is what the strict > gives over an ordered
  ;; list, and what makes the merge order match the book's.
  (let ((best nil) (count 0))
    (dolist (cell pair-freqs best)
      (when (or (null best) (> (cdr cell) count))
        (setq best (car cell))
        (setq count (cdr cell))))))

(defun bpe-merge-split (split a b)
  ;; One split with every adjacent (a b) replaced by their concatenation.
  (let ((out nil) (p split))
    (do ()
        ((null p) (reverse out))
      (if (and (cdr p) (string= (car p) a) (string= (cadr p) b))
          (progn
            (setq out (cons (concatenate 'string a b) out))
            (setq p (cddr p)))
          (progn
            (setq out (cons (car p) out))
            (setq p (cdr p)))))))

(defun bpe-merge-pair (a b splits)
  ;; The merge applied to every word (the book's merge_pair), in place.
  (dolist (cell splits splits)
    (unless (null (cdr (cdr cell)))
      (rplacd cell (bpe-merge-split (cdr cell) a b)))))

(defun bpe-train (corpus &key (num-merges 10))
  ;; The BPE learner: start from the characters, then repeatedly merge the most
  ;; frequent adjacent pair into one token. Returns (vocabulary merges), the
  ;; merges being (a b merged) in the order they were learned -- that ORDER is
  ;; the model, since tokenizing replays it.
  (let* ((word-freqs (bpe-word-freqs corpus))
         (alphabet (bpe-alphabet word-freqs))
         (splits (bpe-splits word-freqs))
         (vocabulary (cons "" alphabet))
         (merges nil))
    (dotimes (i num-merges)
      (let* ((pair-freqs (bpe-pair-freqs splits word-freqs))
             (best (bpe-best-pair pair-freqs)))
        (when (null best) (return))
        (let ((merged (concatenate 'string (car best) (cadr best))))
          (bpe-merge-pair (car best) (cadr best) splits)
          (setq merges (cons (list (car best) (cadr best) merged) merges))
          (setq vocabulary (append vocabulary (list merged))))))
    (list vocabulary (reverse merges))))

(defun bpe-tokenize (merges text)
  ;; New text through the learned merges: split into characters, then replay
  ;; every merge in the order it was learned.
  (let ((splits
         (mapcar (lambda (word)
                   (let ((out nil))
                     (dotimes (i (length word))
                       (setq out (cons (string (char word i)) out)))
                     (reverse out))) (split-on-whitespace text)))
        (out nil))
    (dolist (merge merges)
      (setq splits
            (mapcar
             (lambda (split) (bpe-merge-split split (car merge) (cadr merge)))
             splits)))
    (dolist (split splits (reverse out))
      (dolist (token split) (setq out (cons token out))))))

;; --- the demonstrations ------------------------------------------------------

(format t "=== 3.2.1 word-level tokenization ===~%")

(defparameter *text* "I like apples. You like oranges, not apples.")

(format t "original text:      ~a~%" *text*)
(format t "tokens:             ~s~%" (tokenize-text *text*))

(defparameter *preprocessed* (tokenize-text (preprocess-text *text*)))

(format t "preprocessed:       ~s~%" *preprocessed*)

(defparameter *vocabulary*
  '(("<PAD>" . 0) ("<UNK>" . 1) ("i" . 2) ("like" . 3) ("apples" . 4) ("." . 5)
    ("you" . 6) ("oranges" . 7) ("," . 8) ("not" . 9)))

(format t "token ids:          ~s~%"
        (assign-token-ids *preprocessed* *vocabulary*))
(format t "built vocabulary:   ~s~%" (build-vocabulary *preprocessed*))

(format t "~%=== 3.2.2 special tokens and truncation ===~%")

(format t "with special:       ~s~%"
        (add-special-tokens '("i" "like" "apples" ".") 10))
(format t "truncated:          ~s~%"
        (truncate-tokens '("[CLS]" "i" "like" "apples" "." "you" "like"
                           "oranges" "," "not" "apples" "." "[SEP]") 10))

(format t "~%=== 3.2.3 the embedding tables ===~%")

(linalg:seed 42)

(defparameter *token-embedding* (torch:embedding 10 128))

(defparameter *token-ids* (torch:pad-sequence '((0 1 2 3 4) (5 6 7 8 9))))

(format t "input embeddings:   ~a~%"
        (torch:shape (torch:forward *token-embedding* *token-ids*)))

(defparameter *position-embedding* (torch:embedding 5 128))

(format t "position embeddings:~a~%"
        (torch:shape
         (torch:forward *position-embedding*
                        (torch:pad-sequence (list (list 0 1 2 3 4))))))

(format t "~%=== 3.2.4 byte-pair encoding ===~%")

(defparameter *corpus*
  '("Large language models are transforming the landscape of natural language processing."
    "Understanding tokenization is crucial for building efficient models."
    "This chapter explores the intricacies of BPE and its impact on LLM performance."
    "By mastering these techniques, you can enhance the capabilities of your models."
    "Each token in a sequence carries semantic meaning and contextual information."
    "Modern tokenizers split text into meaningful subword units called tokens."
    "A well-designed token vocabulary improves model efficiency and accuracy."
    "Token-level representations enable better handling of rare and unseen words."
    "Tokenization strategies directly affect how models process and understand text."
    "The choice of tokenization method influences both training speed and final performance."
    "Effective token boundaries help preserve linguistic structures in the data."
    "Language models and LLMs benefit from sophisticated tokenization approaches."))

(defparameter *bpe* (bpe-train *corpus* :num-merges 100))

(format t "vocabulary:         ~s~%" (car *bpe*))
(format t "merges:             ~s~%" (cadr *bpe*))

(defparameter *test-text* "Tokenization improves LLMs.")

(format t "original text:      ~a~%" *test-text*)
(format t "bpe tokens:         ~s~%" (bpe-tokenize (cadr *bpe*) *test-text*))
