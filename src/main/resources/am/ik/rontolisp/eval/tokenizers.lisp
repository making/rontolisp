;; The tokenizer package: the two BPE tokenizers a published language model
;; ships with, written in rontolisp itself so a single implementation runs on
;; every backend -- the interpreter loads these definitions lazily on the first
;; use of a tokenizer: function, and the compile path splices them into the
;; program when it references the package (see TokenizersLibrary.java).
;;
;; Two model kinds behind one tokenizer:encode / tokenizer:decode:
;;
;;   byte-level BPE  GPT-2's shape -- what SmolLM2, Qwen 2.5 / 3 / 3.5, Llama 3
;;                   and every other current small model uses. A pre-tokenizer
;;                   cuts the text into words, each word's UTF-8 bytes are mapped
;;                   through GPT-2's byte-to-character table, and a RANKED merge
;;                   list is applied greedily by rank (tokenizer:make-bpe).
;;   SentencePiece   Llama 2 and TinyLlama's: pieces with SCORES, greedy merges
;;                   by score, and the byte-fallback pieces <0x00>..<0xFF> for
;;                   anything the vocabulary misses
;;                   (tokenizer:make-sentencepiece).
;;
;; The vocabulary is an ARGUMENT, never a file this library opens: tokenizer:
;; does no I/O at all, so the same definitions run in the browser playground and
;; compile to both WASM backends. A GGUF reader hands make-bpe the
;; tokenizer.ggml.tokens / merges fields, a safetensors model's tokenizer.json
;; hands it vocab / merges, and a test hands it a fixture -- .kb/tokenizers.md.
;;
;; Portability constraints honored here (like linalg.lisp, .kb/linalg.md): do
;; loops always declare at least one variable, parameters are never assigned with
;; setq (let-rebound instead), and every buffer is a pre-sized fill-pointer
;; vector filled with vector-push rather than an adjustable one.
;;
;; NOT here, deliberately: Unicode NORMALIZATION. Qwen's tokenizer.json declares
;; an NFC normalizer and llama.cpp does not implement it either; NFC is the
;; identity on text that is already composed, which text from a keyboard, a file
;; or a chat template is. Measured 2026-09-03: on a corpus built out of raw
;; combining marks NFC changes 649 of 2000 strings and every one of those changes
;; the ids, while on natural text (Latin accents, kana, hangul, Vietnamese) it
;; changes nothing. Compose the text before encoding it if it can carry
;; decomposed marks.

;;; --- the GPT-2 byte <-> character table ---------------------------------------
;;; A byte-level BPE never sees a byte: it sees the CHARACTER GPT-2 assigned to
;;; that byte, so that every byte is a printable, non-whitespace code point and a
;;; merge list is plain text. The 188 bytes that are already printable Latin-1
;;; map to themselves; the other 68 map to U+0100.. in increasing byte order.

(defun tokenizer::%byte-printable-p (b)
  (or (and (>= b 33) (<= b 126)) (and (>= b 161) (<= b 172)) (>= b 174)))

(defparameter tokenizer::*byte-to-char*
  (let ((v (make-array 256)) (n 0))
    (dotimes (b 256 v)
      (if (tokenizer::%byte-printable-p b)
          (setf (aref v b) (code-char b))
          (progn
            (setf (aref v b) (code-char (+ 256 n)))
            (setq n (+ n 1)))))))

(defparameter tokenizer::*char-to-byte*
  (let ((h (make-hash-table :test 'eql)))
    (dotimes (b 256 h)
      (setf (gethash (aref tokenizer::*byte-to-char* b) h) b))))

;;; --- UTF-8 --------------------------------------------------------------------
;;; A character is a code point on every backend, so these two are the whole of
;;; the conversion between a rontolisp string and the bytes a tokenizer counts.

(defun tokenizer::%utf8-push (cp out)
  ;; Appends the UTF-8 encoding of the code point CP to OUT, a fill-pointer
  ;; vector the caller sized for four bytes per character.
  (cond ((< cp 128) (vector-push cp out))
        ((< cp 2048)
         (vector-push (+ 192 (floor cp 64)) out)
         (vector-push (+ 128 (mod cp 64)) out))
        ((< cp 65536)
         (vector-push (+ 224 (floor cp 4096)) out)
         (vector-push (+ 128 (mod (floor cp 64) 64)) out)
         (vector-push (+ 128 (mod cp 64)) out))
        (t
         (vector-push (+ 240 (floor cp 262144)) out)
         (vector-push (+ 128 (mod (floor cp 4096) 64)) out)
         (vector-push (+ 128 (mod (floor cp 64) 64)) out)
         (vector-push (+ 128 (mod cp 64)) out)))
  out)

(defun tokenizer::%utf8-decode (bytes)
  ;; A byte vector as a string. A truncated trailing sequence -- which is what a
  ;; half-generated character looks like -- is dropped rather than signalled.
  (let ((chars '()) (i 0) (n (length bytes)))
    (loop while (< i n)
          do
            (let* ((b0 (aref bytes i))
                   (len
                    (cond ((< b0 128) 1) ((< b0 224) 2) ((< b0 240) 3) (t 4)))
                   (cp
                    (cond ((= len 1) b0)
                          ((= len 2) (mod b0 32))
                          ((= len 3) (mod b0 16))
                          (t (mod b0 8)))))
              (if (> (+ i len) n)
                  (setq i n)
                  (progn
                    (dotimes (k (- len 1))
                      (setq cp (+ (* cp 64) (mod (aref bytes (+ i 1 k)) 64))))
                    (push (code-char cp) chars)
                    (setq i (+ i len))))))
    (coerce (nreverse chars) 'string)))

(defun tokenizer::%byte-level-string (text)
  ;; TEXT's UTF-8 bytes, each mapped to its GPT-2 character: the spelling the
  ;; vocabulary and the merge list are written in.
  (let ((bytes (make-array (* 4 (length text)) :fill-pointer 0)))
    (dotimes (i (length text))
      (tokenizer::%utf8-push (char-code (char text i)) bytes))
    (let ((out (make-array (fill-pointer bytes))))
      (dotimes (i (fill-pointer bytes) (coerce out 'string))
        (setf (aref out i) (aref tokenizer::*byte-to-char* (aref bytes i)))))))

;;; --- Unicode general categories -------------------------------------------------
;;; Every pre-tokenizer below is written against \p{L}, \p{N} and \p{M}, so those
;;; three classes are all this package needs of Unicode: one shared range table
;;; each, binary-searched, rather than a regex engine. The tables are at the end
;;; of this file. \s is \p{White_Space}, 25 code points, spelled out.

(defun tokenizer::%expand-ranges (deltas)
  ;; The delta-encoded table as a flat #(start end start end ...) vector.
  (let ((out (make-array (length deltas))) (prev -1))
    (do ((i 0 (+ i 2)))
        ((>= i (length deltas)) out)
      (let* ((start (+ prev 1 (aref deltas i)))
             (end (+ start (aref deltas (+ i 1)))))
        (setf (aref out i) start)
        (setf (aref out (+ i 1)) end)
        (setq prev end)))))

(defun tokenizer::%in-ranges-p (ranges cp)
  (let ((lo 0) (hi (- (floor (length ranges) 2) 1)))
    (loop
      (when (> lo hi) (return nil))
      (let* ((mid (floor (+ lo hi) 2))
             (start (aref ranges (* 2 mid)))
             (end (aref ranges (+ (* 2 mid) 1))))
        (cond ((< cp start) (setq hi (- mid 1)))
              ((> cp end) (setq lo (+ mid 1)))
              (t (return t)))))))

(defun tokenizer::%letterp (ch)
  (tokenizer::%in-ranges-p tokenizer::*letter-ranges* (char-code ch)))

(defun tokenizer::%numberp (ch)
  (tokenizer::%in-ranges-p tokenizer::*number-ranges* (char-code ch)))

(defun tokenizer::%markp (ch)
  (tokenizer::%in-ranges-p tokenizer::*mark-ranges* (char-code ch)))

(defun tokenizer::%spacep (ch)
  ;; \p{White_Space}, which is NOT a language's isspace: U+001C..U+001F are
  ;; control characters, and the Rust regex the reference tokenizers are written
  ;; against counts them as neither space nor symbol.
  (let ((cp (char-code ch)))
    (or (and (>= cp 9) (<= cp 13)) (= cp 32) (= cp 133) (= cp 160) (= cp 5760)
        (and (>= cp 8192) (<= cp 8202)) (= cp 8232) (= cp 8233) (= cp 8239)
        (= cp 8287) (= cp 12288))))

(defun tokenizer::%newlinep (ch) (or (char= ch #\Newline) (char= ch #\Return)))

;;; --- the pre-tokenizers -----------------------------------------------------------
;;; Four fixed shapes, hand-coded as scanners rather than run through a regex
;;; engine -- which is what llama.cpp does, and what keeps a Unicode regex engine
;;; out of this package. Each is an ALTERNATION matched leftmost-FIRST (the Rust
;;; regex crate's semantics, which the reference implementation is written in):
;;; at each position the alternatives are tried in order and the first that
;;; matches wins, so the order below is load-bearing.
;;;
;;;   :gpt2    's-suffixes | ` ?\p{L}+` | ` ?\p{N}+` | ` ?[^\s\p{L}\p{N}]+`
;;;            | `\s+(?!\S)` | `\s+`                    -- GPT-2's ByteLevel
;;;   :smollm  every \p{N} character split off on its own first, then :gpt2
;;;            -- SmolLM2, whose tokenizer.json puts a Digits pre-tokenizer in
;;;            front of the ByteLevel one
;;;   :llama3  's-suffixes (case-insensitive) | `[^\r\n\p{L}\p{N}]?\p{L}+`
;;;            | `\p{N}{1,3}` | ` ?[^\s\p{L}\p{N}]+[\r\n]*` | `\s*[\r\n]+`
;;;            | `\s+(?!\S)` | `\s+`                    -- Llama 3, LFM2.5
;;;   :qwen2   :llama3 with `\p{N}`, one digit at a time -- Qwen 2.5, Qwen 3
;;;   :qwen35  :qwen2 with `[\p{L}\p{M}]+` as the word class (and \p{M} out of
;;;            the symbol class), so a combining mark stays with its letter
;;;            -- Qwen 3.5 through 3.8

(defparameter tokenizer::*contractions*
  (vector "'s" "'t" "'re" "'ve" "'m" "'ll" "'d"))

(defun tokenizer::%normalize-kind (kind)
  ;; A keyword, or the name llama.cpp writes into tokenizer.ggml.pre.
  (cond ((eq kind :gpt2) :gpt2)
   ((eq kind :smollm) :smollm)
   ((eq kind :llama3) :llama3)
   ((eq kind :qwen2) :qwen2)
   ((eq kind :qwen35) :qwen35)
   ((not (stringp kind)) (error "tokenizer: unknown pre-tokenizer ~s" kind))
   ((or (string= kind "gpt-2") (string= kind "gpt2") (string= kind "default"))
    :gpt2)
   ((string= kind "smollm") :smollm)
   ;; a family whose GGUF names the Llama 3 shape after ITSELF: LFM2 / LFM2.5's
   ;; tokenizer.json holds that pattern character for character, and llama.cpp
   ;; maps `lfm2` to LLAMA_VOCAB_PRE_TYPE_LLAMA3 with the spellings beside it
   ((or (string= kind "llama3") (string= kind "llama-v3")
        (string= kind "llama-bpe") (string= kind "lfm2"))
    :llama3)
   ((or (string= kind "qwen2") (string= kind "qwen3")) :qwen2)
   ((string= kind "qwen35") :qwen35)
   (t (error "tokenizer: unknown pre-tokenizer ~s" kind))))

(defun tokenizer::%contraction-length (text i fold)
  ;; The length of the 's-suffix at I, or 0. FOLD is the (?i:) of the Llama 3 and
  ;; Qwen shapes; GPT-2's own alternation is case-SENSITIVE.
  (let ((n (length text)) (hit 0))
    (dotimes (k (length tokenizer::*contractions*) hit)
      (when (= hit 0)
        (let* ((c (aref tokenizer::*contractions* k)) (len (length c)))
          (when (<= (+ i len) n)
            (let ((ok t))
              (dotimes (j len)
                (let ((a (char text (+ i j))) (b (char c j)))
                  (unless (or (char= a b)
                              (and fold (char= (char-downcase a) b)))
                    (setq ok nil))))
              (when ok (setq hit len)))))))))

(defun tokenizer::%space-run-end (text i)
  (let ((n (length text)) (k i))
    (loop while (and (< k n) (tokenizer::%spacep (char text k)))
          do (setq k (+ k 1)))
    k))

(defun tokenizer::%last-newline (text start end)
  (let ((last -1))
    (do ((j start (+ j 1)))
        ((>= j end) last)
      (when (tokenizer::%newlinep (char text j)) (setq last j)))))

(defun tokenizer::%whitespace-length (text i newline-rule)
  ;; `\s*[\r\n]+` (only where NEWLINE-RULE -- everywhere but GPT-2's shape, which
  ;; has no such alternative), then `\s+(?!\S)`, then `\s+`. Greedy matching with
  ;; backtracking has exactly three outcomes on a whitespace run: a run holding a
  ;; newline ends just after its LAST newline; a run that reaches the end of the
  ;; text is taken whole; any other run gives up its last character, which is the
  ;; space the next word carries.
  (let* ((n (length text))
         (k (tokenizer::%space-run-end text i))
         (nl
          (if (and newline-rule (> k i))
              (tokenizer::%last-newline text i k)
              -1)))
    (cond ((= k i) 0)
          ((>= nl 0) (- (+ nl 1) i))
          ((= k n) (- k i))
          ((> (- k 1) i) (- (- k 1) i))
          (t (- k i)))))

(defun tokenizer::%gpt2-length (text i)
  ;; One match of GPT-2's ByteLevel alternation at I.
  (let ((n (length text)) (hit (tokenizer::%contraction-length text i nil)))
    (when (= hit 0)
      (let* ((j (if (char= (char text i) #\Space) (+ i 1) i)) (k j))
        (loop while (and (< k n) (tokenizer::%letterp (char text k)))
              do (setq k (+ k 1)))
        (when (> k j) (setq hit (- k i)))))
    (when (= hit 0)
      (let* ((j (if (char= (char text i) #\Space) (+ i 1) i)) (k j))
        (loop while (and (< k n) (tokenizer::%numberp (char text k)))
              do (setq k (+ k 1)))
        (when (> k j) (setq hit (- k i)))))
    (when (= hit 0)
      (let* ((j (if (char= (char text i) #\Space) (+ i 1) i)) (k j))
        (loop while
                (and (< k n) (not (tokenizer::%spacep (char text k)))
                     (not (tokenizer::%letterp (char text k)))
                     (not (tokenizer::%numberp (char text k))))
              do (setq k (+ k 1)))
        (when (> k j) (setq hit (- k i)))))
    (when (= hit 0) (setq hit (tokenizer::%whitespace-length text i nil)))
    hit))

(defun tokenizer::%word-char-p (ch marks)
  (or (tokenizer::%letterp ch) (and marks (tokenizer::%markp ch))))

(defun tokenizer::%split-length (text i digits marks)
  ;; One match of the Llama 3 / Qwen alternation at I. DIGITS caps
  ;; `\p{N}{1,DIGITS}` (3 for Llama 3, 1 for Qwen); MARKS puts \p{M} into the word
  ;; class and out of the symbol class (Qwen 3.5).
  (let ((n (length text)) (hit (tokenizer::%contraction-length text i t)))
    (when (= hit 0)
      ;; `[^\r\n\p{L}\p{N}]?\p{L}+`: the optional character is greedy, so take it
      ;; first and fall back to not taking it.
      (let ((head (char text i)))
        (when (and (not (tokenizer::%newlinep head))
                   (not (tokenizer::%letterp head))
                   (not (tokenizer::%numberp head)))
          (let ((k (+ i 1)))
            (loop while
                    (and (< k n) (tokenizer::%word-char-p (char text k) marks))
                  do (setq k (+ k 1)))
            (when (> k (+ i 1)) (setq hit (- k i)))))
        (when (= hit 0)
          (let ((k i))
            (loop while
                    (and (< k n) (tokenizer::%word-char-p (char text k) marks))
                  do (setq k (+ k 1)))
            (when (> k i) (setq hit (- k i)))))))
    (when (= hit 0)
      (let ((k i))
        (loop while
                (and (< k n) (< (- k i) digits)
                     (tokenizer::%numberp (char text k)))
              do (setq k (+ k 1)))
        (when (> k i) (setq hit (- k i)))))
    (when (= hit 0)
      ;; ` ?[^\s\p{L}\p{N}]+[\r\n]*`
      (let* ((j (if (char= (char text i) #\Space) (+ i 1) i)) (k j))
        (loop while
                (and (< k n) (not (tokenizer::%spacep (char text k)))
                     (not (tokenizer::%letterp (char text k)))
                     (not (tokenizer::%numberp (char text k)))
                     (not (and marks (tokenizer::%markp (char text k)))))
              do (setq k (+ k 1)))
        (when (> k j)
          (loop while (and (< k n) (tokenizer::%newlinep (char text k)))
                do (setq k (+ k 1)))
          (setq hit (- k i)))))
    (when (= hit 0) (setq hit (tokenizer::%whitespace-length text i t)))
    hit))

(defun tokenizer::%scan (text kind)
  ;; TEXT cut into pre-tokens by KIND's alternation. :smollm's leading Digits
  ;; pre-tokenizer is a separate pass -- see tokenizer:pre-tokenize.
  (let ((out '()) (i 0) (n (length text)))
    (loop while (< i n)
          do
            (let ((len
                   (if (eq kind :gpt2)
                       (tokenizer::%gpt2-length text i)
                       (tokenizer::%split-length text i
                                                 (if (eq kind :llama3) 3 1)
                                                 (eq kind :qwen35)))))
              (when (<= len 0)
                (error "tokenizer: no pre-token matched at position ~a of ~s" i
                       text))
              (push (subseq text i (+ i len)) out)
              (setq i (+ i len))))
    (nreverse out)))

(defun tokenizer::%split-digits (text)
  ;; The Digits(individual_digits) pre-tokenizer SmolLM2 runs before the GPT-2
  ;; alternation: every \p{N} character becomes a chunk of its own. \p{N}, not
  ;; the decimal digits alone -- U+00BD VULGAR FRACTION ONE HALF and U+216B ROMAN
  ;; NUMERAL TWELVE are split off too.
  (let ((out '()) (start 0) (n (length text)))
    (dotimes (i n)
      (when (tokenizer::%numberp (char text i))
        (when (> i start) (push (subseq text start i) out))
        (push (subseq text i (+ i 1)) out)
        (setq start (+ i 1))))
    (when (< start n) (push (subseq text start n) out))
    (nreverse out)))

(defun tokenizer:pre-tokenize (kind text)
  "Cuts TEXT into pre-tokens the way KIND's pre-tokenizer does, as a list of
strings. KIND is :gpt2, :smollm, :llama3, :qwen2 or :qwen35, or the equivalent
GGUF tokenizer.ggml.pre string. This is the half of a byte-level BPE that is not
data, so it is exported on its own: the ids only follow once the cut is right."
  (let ((k (tokenizer::%normalize-kind kind)))
    (if (eq k :smollm)
        (let ((out '()))
          (dolist (chunk (tokenizer::%split-digits text))
            (dolist (piece (tokenizer::%scan chunk :gpt2)) (push piece out)))
          (nreverse out))
        (tokenizer::%scan text k))))

;;; --- the tokenizer record -----------------------------------------------------------

(defstruct (tokenizer::%tk (:constructor tokenizer::%make-tk))
  ;; model          :bpe or :sentencepiece
  ;; kind           the pre-tokenizer keyword (:bpe only)
  ;; tokens         id -> token string; the byte-level spelling for :bpe
  ;; index          token string -> id
  ;; ranks          "left right" -> merge rank (:bpe only)
  ;; scores         id -> merge score (:sentencepiece only)
  ;; specials       the special token strings, longest first
  ;; firsts         the distinct first characters of SPECIALS, as a string
  ;; special-set    the same strings as a membership table, for decode
  ;; ignore-merges  Llama 3's flag: a whole pre-token that is in the vocabulary
  ;;                IS that id, the merges not consulted
  ;; bos, eos       the ids tokenizer:encode's :bos / :eos add, or nil
  (model :bpe)
  (kind :gpt2)
  (tokens (vector))
  (index nil)
  (ranks nil)
  (scores nil)
  (specials '())
  (firsts "")
  (special-set nil)
  (ignore-merges nil)
  (bos nil)
  (eos nil))

(defun tokenizer::%index-of (tokens)
  ;; token string -> id, first id wins (a vocabulary that repeats a string keeps
  ;; the lowest id, as the reference implementations do).
  (let ((h (make-hash-table :test 'equal)))
    (dotimes (i (length tokens) h)
      (let ((tok (aref tokens i)))
        (when (and tok (not (gethash tok h))) (setf (gethash tok h) i))))))

(defun tokenizer::%sort-specials (specials)
  ;; Longest first, so the leftmost-longest scan in %special-at needs no
  ;; backtracking.
  (let ((v (coerce specials 'vector)))
    (dotimes (i (length v) (coerce v 'list))
      (do ((j (- (length v) 1) (- j 1)))
          ((<= j (+ i 1)))
        (when (> (length (aref v j)) (length (aref v (- j 1))))
          (let ((tmp (aref v j)))
            (setf (aref v j) (aref v (- j 1)))
            (setf (aref v (- j 1)) tmp)))))))

(defun tokenizer::%firsts-of (specials)
  (let ((out '()))
    (dolist (s specials)
      (when (and (> (length s) 0) (not (member (char s 0) out)))
        (push (char s 0) out)))
    (coerce (nreverse out) 'string)))

(defun tokenizer::%set-of (specials)
  (let ((h (make-hash-table :test 'equal)))
    (dolist (s specials h) (setf (gethash s h) t))))

(defun tokenizer:make-bpe
    (tokens merges &key (kind :gpt2) specials ignore-merges bos eos)
  "Builds a byte-level BPE tokenizer. TOKENS is a sequence of token strings
indexed by id, in the byte-level spelling the merge list uses -- a GGUF's
tokenizer.ggml.tokens, or a tokenizer.json's vocab with its added_tokens filled
in. MERGES is the ranked merge list, best rank first, each entry either the
string \"left right\" or a two-element sequence. KIND names the pre-tokenizer
(see tokenizer:pre-tokenize). SPECIALS lists the token strings matched whole,
before pre-tokenization. IGNORE-MERGES is Llama 3's flag: a pre-token that is
itself in the vocabulary is that id, the merges not consulted. BOS and EOS are
the ids tokenizer:encode adds when asked for them."
  (let* ((tv (coerce tokens 'vector))
         (mv (coerce merges 'vector))
         (ranks (make-hash-table :test 'equal))
         (sorted (tokenizer::%sort-specials specials)))
    (dotimes (r (length mv))
      (let* ((m (aref mv r))
             (key
              (if (stringp m) m (concatenate 'string (elt m 0) " " (elt m 1)))))
        (unless (gethash key ranks) (setf (gethash key ranks) r))))
    (tokenizer::%make-tk :model :bpe
                         :kind (tokenizer::%normalize-kind kind)
                         :tokens tv
                         :index (tokenizer::%index-of tv)
                         :ranks ranks
                         :specials sorted
                         :firsts (tokenizer::%firsts-of sorted)
                         :special-set (tokenizer::%set-of sorted)
                         :ignore-merges ignore-merges
                         :bos bos
                         :eos eos)))

(defun tokenizer:make-sentencepiece
    (pieces scores &key specials (bos 1) (eos 2))
  "Builds a SentencePiece-style BPE tokenizer -- Llama 2 and TinyLlama's. PIECES
is a sequence of piece strings indexed by id and SCORES the matching merge
scores. U+2581 is translated to a space, so a GGUF's tokenizer.ggml.tokens and a
llama2.c tokenizer.bin's already-translated pieces are both accepted as they
come. The byte-fallback pieces <0x00>..<0xFF> are looked up by name, so they need
not sit at any particular id."
  (let* ((pv (coerce pieces 'vector))
         (tv (make-array (length pv)))
         (sorted (tokenizer::%sort-specials specials)))
    (dotimes (i (length pv))
      (setf (aref tv i) (tokenizer::%untag-space (aref pv i))))
    (tokenizer::%make-tk :model :sentencepiece
                         :kind nil
                         :tokens tv
                         :index (tokenizer::%index-of tv)
                         :scores (coerce scores 'vector)
                         :specials sorted
                         :firsts (tokenizer::%firsts-of sorted)
                         :special-set (tokenizer::%set-of sorted)
                         :bos bos
                         :eos eos)))

(defun tokenizer::%untag-space (piece)
  ;; SentencePiece writes a word-leading space as U+2581 LOWER ONE EIGHTH BLOCK;
  ;; llama2.c's exporter already replaced it with a space. Accept both spellings
  ;; as one.
  (if (or (null piece) (not (find (code-char 9601) piece)))
      piece
      (let ((out (make-array (length piece))))
        (dotimes (i (length piece) (coerce out 'string))
          (setf (aref out i)
                (if (char= (char piece i) (code-char 9601))
                    #\Space
                    (char piece i)))))))

(defun tokenizer:vocabulary-size (tk)
  "The number of ids TK's vocabulary defines."
  (length (tokenizer::%tk-tokens tk)))

(defun tokenizer:token-string (tk id)
  "The token string TK gives the id ID. For a byte-level BPE this is the
byte-level spelling -- what the merge list is written in, not text; use
tokenizer:decode to turn ids back into text."
  (aref (tokenizer::%tk-tokens tk) id))

(defun tokenizer:token-id (tk token)
  "The id of the token string TOKEN, or nil when the vocabulary has no such
token."
  (gethash token (tokenizer::%tk-index tk)))

(defun tokenizer:bos-id (tk)
  "TK's beginning-of-sequence id, or nil."
  (tokenizer::%tk-bos tk))

(defun tokenizer:eos-id (tk)
  "TK's end-of-sequence id, or nil."
  (tokenizer::%tk-eos tk))

;;; --- the byte-level merge loop ----------------------------------------------------

(defun tokenizer::%bpe-word (word ranks)
  ;; The greedy merge: repeatedly take the adjacent pair with the BEST (lowest)
  ;; rank and merge every occurrence of it, left to right, until no adjacent pair
  ;; has a rank. Returns the pieces as a list of strings.
  (let ((n (length word)) (parts (make-array (max (length word) 1))) (count 0))
    (dotimes (i n) (setf (aref parts i) (subseq word i (+ i 1))))
    (setq count n)
    (loop
      (when (< count 2) (return))
      (let ((best -1) (best-idx -1))
        (dotimes (i (- count 1))
          (let ((r
                 (gethash
                  (concatenate 'string (aref parts i) " " (aref parts (+ i 1)))
                  ranks)))
            (when (and r (or (< best 0) (< r best))) (setq best r best-idx i))))
        (when (< best-idx 0) (return))
        (let* ((a (aref parts best-idx))
               (b (aref parts (+ best-idx 1)))
               (merged (concatenate 'string a b))
               (w 0)
               (i 0))
          (loop while (< i count)
                do
                  (if (and (< i (- count 1)) (string= (aref parts i) a)
                           (string= (aref parts (+ i 1)) b))
                      (progn
                        (setf (aref parts w) merged)
                        (setq w (+ w 1))
                        (setq i (+ i 2)))
                      (progn
                        (setf (aref parts w) (aref parts i))
                        (setq w (+ w 1))
                        (setq i (+ i 1)))))
          (setq count w))))
    (let ((out '()))
      (dotimes (i count out) (push (aref parts (- count 1 i)) out)))))

(defun tokenizer::%encode-piece (tk piece out)
  (let ((word (tokenizer::%byte-level-string piece))
        (index (tokenizer::%tk-index tk)))
    (if (and (tokenizer::%tk-ignore-merges tk) (gethash word index))
        (vector-push (gethash word index) out)
        (dolist (part (tokenizer::%bpe-word word (tokenizer::%tk-ranks tk)))
          (let ((id (gethash part index)))
            (unless id
              (error
               "tokenizer: the vocabulary has no id for the byte-level token ~s"
               part))
            (vector-push id out))))
    out))

;;; --- the SentencePiece merge loop --------------------------------------------------

(defun tokenizer::%byte-piece-value (piece)
  ;; "<0xNN>" -> the byte NN, else nil. A SentencePiece vocabulary spells a byte it
  ;; has no piece for this way, so the decode has to read it back.
  (if (and (= (length piece) 6) (char= (char piece 0) #\<)
           (char= (char piece 1) #\0) (char= (char piece 2) #\x)
           (char= (char piece 5) #\>))
      (parse-integer piece :start 3 :end 5 :radix 16)
      nil))

(defun tokenizer::%byte-piece-id (tk b)
  (let ((digits "0123456789ABCDEF"))
    (gethash (concatenate 'string "<0x" (string (char digits (floor b 16)))
                          (string (char digits (mod b 16))) ">")
             (tokenizer::%tk-index tk))))

(defun tokenizer::%encode-sentencepiece (tk text out)
  ;; run.c's encode(): the dummy-prefix space, one token per character (or the
  ;; character's byte-fallback pieces when the vocabulary has no such piece), then
  ;; repeatedly merge the adjacent pair whose concatenation scores highest. Only
  ;; the region this call appends is merged, so a special token before it is a
  ;; barrier.
  (let ((index (tokenizer::%tk-index tk))
        (tokens (tokenizer::%tk-tokens tk))
        (scores (tokenizer::%tk-scores tk))
        (base (fill-pointer out)))
    (when (> (length text) 0)
      (let ((sp (gethash " " index))) (when sp (vector-push sp out))))
    (dotimes (i (length text))
      (let* ((c (subseq text i (+ i 1))) (id (gethash c index)))
        (if id
            (vector-push id out)
            (let ((bytes (make-array 4 :fill-pointer 0)))
              (tokenizer::%utf8-push (char-code (char text i)) bytes)
              (dotimes (k (fill-pointer bytes))
                (let ((bid (tokenizer::%byte-piece-id tk (aref bytes k))))
                  (unless bid
                    (error "tokenizer: the vocabulary has no byte-fallback piece for ~a"
                           (aref bytes k)))
                  (vector-push bid out)))))))
    (loop
      (let ((best-score nil) (best-id nil) (best-idx nil))
        (do ((i base (+ i 1)))
            ((>= i (- (fill-pointer out) 1)))
          (let* ((merged
                  (concatenate 'string (aref tokens (aref out i))
                               (aref tokens (aref out (+ i 1)))))
                 (id (gethash merged index)))
            (when (and id
                       (or (null best-score) (> (aref scores id) best-score)))
              (setq best-score (aref scores id))
              (setq best-id id)
              (setq best-idx i))))
        (unless best-idx (return))
        (setf (aref out best-idx) best-id)
        (let ((n (fill-pointer out)))
          (do ((i (+ best-idx 1) (+ i 1)))
              ((>= i (- n 1)))
            (setf (aref out i) (aref out (+ i 1))))
          (setf (fill-pointer out) (- n 1)))))
    out))

;;; --- encode / decode ------------------------------------------------------------------

(defun tokenizer::%special-at (tk text i)
  ;; The special token that starts at I, leftmost-LONGEST, or nil.
  (if (not (find (char text i) (tokenizer::%tk-firsts tk)))
      nil
      (let ((hit nil) (n (length text)))
        (dolist (s (tokenizer::%tk-specials tk) hit)
          (when (and (null hit) (<= (+ i (length s)) n)
                     (string= s text :start2 i :end2 (+ i (length s))))
            (setq hit s))))))

(defun tokenizer::%encode-chunk (tk chunk out)
  (if (eq (tokenizer::%tk-model tk) :bpe)
      (dolist (piece (tokenizer:pre-tokenize (tokenizer::%tk-kind tk) chunk))
        (tokenizer::%encode-piece tk piece out))
      (tokenizer::%encode-sentencepiece tk chunk out))
  out)

(defun tokenizer:encode (tk text &key bos eos)
  "Encodes TEXT with TK and returns the list of token ids. :BOS and :EOS add TK's
beginning- and end-of-sequence ids around the result. A special token is matched
whole, before pre-tokenization; everything else goes through TK's model."
  (let ((out (make-array (+ (* 4 (length text)) 8) :fill-pointer 0))
        (index (tokenizer::%tk-index tk))
        (start 0)
        (i 0)
        (n (length text)))
    (when (and bos (tokenizer::%tk-bos tk))
      (vector-push (tokenizer::%tk-bos tk) out))
    (loop while (< i n)
          do
            (let ((s (tokenizer::%special-at tk text i)))
              (if s
                  (progn
                    (when (> i start)
                      (tokenizer::%encode-chunk tk (subseq text start i) out))
                    (vector-push (gethash s index) out)
                    (setq i (+ i (length s)))
                    (setq start i))
                  (setq i (+ i 1)))))
    (when (> n start) (tokenizer::%encode-chunk tk (subseq text start n) out))
    (when (and eos (tokenizer::%tk-eos tk))
      (vector-push (tokenizer::%tk-eos tk) out))
    (coerce out 'list)))

(defun tokenizer:decode-bytes (tk ids)
  "The raw bytes the token ids IDS stand for, as a fill-pointer byte vector. This
is the streaming half of tokenizer:decode: a multi-byte character can straddle
two tokens, so a generation loop accumulates bytes and decodes what is complete."
  (let ((tokens (tokenizer::%tk-tokens tk))
        (bpe (eq (tokenizer::%tk-model tk) :bpe))
        (specials (tokenizer::%tk-special-set tk))
        (room 0))
    (dolist (id ids) (setq room (+ room (* 4 (length (aref tokens id))))))
    (let ((out (make-array (+ room 1) :fill-pointer 0)))
      (dolist (id ids out)
        (let* ((tok (aref tokens id))
               (fallback (if bpe nil (tokenizer::%byte-piece-value tok))))
          (cond (fallback (vector-push fallback out))
                ((and bpe (not (gethash tok specials)))
                 (dotimes (i (length tok))
                   (let ((b (gethash (char tok i) tokenizer::*char-to-byte*)))
                     (unless b
                       (error "tokenizer: ~s is not a byte-level character"
                              (char tok i)))
                     (vector-push b out))))
                (t
                 (dotimes (i (length tok))
                   (tokenizer::%utf8-push (char-code (char tok i)) out)))))))))

(defun tokenizer:decode (tk ids)
  "The text the token ids IDS stand for -- a WHOLE sequence, so that it round-trips
tokenizer:encode. A SentencePiece encode opens with a dummy prefix space, and this
takes it back off, exactly as sentencepiece's own decode does; a generation loop
that decodes one token at a time wants tokenizer:decode-bytes instead, which never
touches what it is given."
  (let ((text (tokenizer::%utf8-decode (tokenizer:decode-bytes tk ids))))
    (if (and (eq (tokenizer::%tk-model tk) :sentencepiece) (> (length text) 0)
             (char= (char text 0) #\Space))
        (subseq text 1)
        text)))

;;; --- the Unicode class tables ----------------------------------------------------------
;;; Three ranges tables -- \p{L}, \p{N}, \p{M} -- delta-encoded so that the whole
;;; of Unicode costs 6 KB of source and a few hundred distinct small integers in a
;;; compiled program's constant pool. Generated from Unicode 15.0.0; regenerate
;;; against the Unicode version the reference tokenizer was built with if a newly
;;; assigned code point ever matters.

(defun tokenizer::%letter-deltas ()
  ;; Unicode general category L as 659 ranges, delta-encoded (gap from the
  ;; previous range's end, then the range's length - 1); Unicode 15.0.0.
  #(65 25 6 25 47 0 10 0 4 0 5 22 1 30 1 457 4 11 14 4 7 0 1 0 129 4 1 1 2 3 1 0
    6 0 1 2 1 0 1 19 1 82 1 138 8 165 1 37 2 0 6 40 71 26 4 3 45 42 35 1 1 98 1
    0 15 1 7 1 10 2 2 0 16 0 1 29 29 88 11 0 24 32 9 1 4 0 5 21 4 0 9 0 3 0 23
    24 7 10 5 23 1 5 17 41 58 53 3 0 18 0 7 9 15 15 4 7 2 1 2 21 1 6 1 0 3 3 3 0
    16 0 13 1 1 2 14 1 10 0 8 5 4 1 2 21 1 6 1 1 1 1 1 1 31 3 1 0 19 2 16 8 1 2
    1 21 1 6 1 1 1 4 3 0 18 0 15 1 23 0 11 7 2 1 2 21 1 6 1 1 1 4 3 0 30 1 1 2
    15 0 17 0 1 5 3 2 1 3 3 1 1 0 1 1 3 1 3 2 3 11 22 0 52 7 1 2 1 22 1 15 3 0
    26 2 2 0 2 1 30 0 4 7 1 2 1 22 1 9 1 4 3 0 31 1 1 1 15 1 17 8 1 2 1 40 2 0
    16 0 5 2 8 2 24 5 5 17 3 23 1 8 1 0 2 6 58 47 1 1 12 6 58 1 1 0 1 4 1 23 1 0
    1 9 1 1 9 0 2 4 1 0 21 3 32 0 63 7 1 35 27 4 115 42 20 0 16 5 4 3 3 0 3 1 7
    2 4 12 12 0 17 37 1 0 5 0 2 42 1 332 1 3 2 6 1 0 1 3 2 40 1 3 2 32 1 3 2 6 1
    0 1 3 2 14 1 56 1 3 2 66 37 15 16 85 2 5 3 619 2 16 1 25 5 74 6 7 7 17 13 18
    14 17 14 12 1 2 15 51 35 0 4 0 67 88 7 4 2 33 1 0 5 69 10 30 49 29 2 4 11 43
    4 25 54 22 9 52 82 0 93 46 17 7 54 29 13 1 10 43 26 35 41 2 10 35 2 8 7 42 2
    2 41 3 1 5 1 1 3 0 5 191 64 277 2 5 2 37 2 5 2 7 1 0 1 0 1 0 1 30 2 52 1 6 1
    0 3 2 1 6 3 3 2 5 4 12 5 2 1 6 116 0 13 0 16 12 101 0 4 0 2 9 1 0 3 4 6 0 1
    0 1 0 1 3 1 10 2 3 5 4 4 0 52 1 2683 228 6 3 3 1 12 37 1 0 5 0 2 55 7 0 16
    22 9 6 1 6 1 6 1 6 1 6 1 6 1 6 1 6 80 0 469 1 42 4 5 1 4 85 6 2 1 89 1 3 5
    42 1 93 17 31 48 15 512 6591 64 22156 67 45 2 268 3 15 10 1 20 46 16 30 2 69
    49 8 2 102 2 63 5 1 1 0 1 4 24 15 1 2 1 3 1 22 29 51 14 49 62 5 3 0 1 1 11
    27 10 22 25 28 7 46 28 0 16 4 1 9 10 4 1 40 23 2 1 7 20 22 3 0 3 49 1 0 3 1
    2 4 2 0 1 0 24 2 2 10 7 2 12 5 2 5 2 5 9 6 1 6 1 42 1 13 6 114 29 11171 12
    22 4 48 8452 365 2 105 38 6 12 4 5 0 1 9 1 12 1 4 1 0 1 1 1 1 1 107 33 362
    18 63 2 53 40 11 116 4 1 134 36 25 6 25 11 88 3 5 2 5 2 5 2 2 35 11 1 25 1
    18 1 1 1 14 2 13 34 122 389 28 3 48 47 31 13 19 1 7 6 37 10 29 2 35 4 7 48
    157 18 35 4 35 4 39 8 51 12 10 1 14 1 6 1 1 1 10 1 14 1 6 1 1 67 310 9 21 10
    7 24 5 1 41 1 8 69 5 2 0 1 43 1 1 3 0 2 22 10 22 9 30 65 18 1 1 10 21 10 25
    70 55 6 1 64 0 15 3 1 2 1 28 42 28 3 28 35 7 1 27 27 53 10 21 10 18 13 17
    110 72 55 50 13 50 13 35 348 41 6 1 78 28 10 0 8 21 42 17 46 20 27 22 12 52
    57 1 2 0 13 44 32 24 26 35 29 0 2 0 8 34 3 0 12 47 14 3 21 0 1 0 35 17 1 24
    19 1 63 6 1 0 1 3 1 14 1 9 7 46 38 7 2 1 2 21 1 6 1 1 1 4 3 0 18 0 12 4 158
    52 18 3 20 2 30 47 20 1 1 0 184 46 41 3 36 47 20 0 59 42 13 0 71 26 37 6 185
    43 116 63 31 7 2 0 2 7 1 1 1 23 15 0 1 0 94 7 2 38 16 0 1 0 28 0 10 39 7 0
    21 0 11 45 19 0 18 72 263 8 1 36 17 0 49 29 112 6 1 1 1 37 21 0 25 5 1 1 1
    31 14 0 327 18 15 0 1 12 1 33 124 0 79 921 230 195 2636 96 15 1071 17 5 4025
    582 8633 568 7 30 17 78 17 29 18 47 16 3 31 20 5 18 688 63 128 74 5 0 66 12
    64 1 1 0 28 6135 8 1237 42 8 8935 3 1 6 1 1 1 290 15 0 29 2 2 0 14 3 8 395
    2308 106 5 12 3 8 7 9 5990 84 1 70 1 1 2 0 2 1 2 3 1 11 1 0 1 6 1 64 1 3 2 7
    1 6 1 27 1 3 1 4 1 0 3 6 1 339 2 24 1 24 1 30 1 24 1 30 1 24 1 30 1 24 1 30
    1 24 1 7 1844 30 6 5 261 61 146 44 10 6 16 0 321 29 18 43 484 27 756 6 1 3 1
    1 1 14 1 196 59 67 7 0 1204 3 1 26 1 1 1 0 2 0 1 9 1 3 1 0 1 0 6 0 4 0 1 0 1
    0 1 2 1 1 1 0 2 0 1 0 1 0 1 0 1 0 1 1 1 0 2 3 1 6 1 3 1 3 1 0 1 9 1 16 5 2 1
    4 1 16 4420 42719 32 4153 6 221 2 5761 14 7472 3103 541 1506 4938 5 4191))

(defun tokenizer::%number-deltas ()
  ;; Unicode general category N as 137 ranges, delta-encoded (gap from the
  ;; previous range's end, then the range's length - 1); Unicode 15.0.0.
  #(48 9 120 1 5 0 2 2 1441 9 134 9 198 9 412 9 118 9 4 5 108 9 118 9 118 9 2 5
    110 12 115 9 8 6 103 9 104 6 7 18 109 9 96 9 118 9 70 19 268 9 70 9 719 19
    881 2 239 9 6 9 22 9 300 9 128 10 165 9 6 9 182 9 86 9 134 9 6 9 1046 0 3 5
    6 9 198 50 2 4 726 59 78 21 630 29 1385 0 777 0 25 8 14 2 343 3 138 9 30 7 1
    14 32 9 39 14 29536 9 188 9 320 5 154 9 38 9 198 9 22 9 86 9 406 9 21270 9
    493 44 12 56 17 1 341 26 36 3 29 0 8 0 134 4 202 9 942 7 25 6 39 8 75 4 22 5
    160 1 2 15 2 45 64 8 52 1 30 2 75 4 104 7 24 7 41 6 330 5 48 9 294 30 158 9
    42 3 112 6 134 29 128 9 60 9 144 9 7 19 251 9 342 9 118 9 374 9 102 9 102 11
    420 18 93 9 758 28 227 9 70 9 422 9 102 20 1067 110 17905 9 86 9 134 9 1 6
    798 22 25641 19 12 19 108 24 1109 49 2368 9 422 9 502 9 973 8 128 9 791 58 1
    2 1 3 76 44 1 14 962 12 2787 9))

(defun tokenizer::%mark-deltas ()
  ;; Unicode general category M as 310 ranges, delta-encoded (gap from the
  ;; previous range's end, then the range's length - 1); Unicode 15.0.0.
  #(768 111 275 6 263 44 1 0 1 1 1 1 1 0 72 10 48 20 16 0 101 6 2 5 2 1 1 3 35 0
    30 26 91 10 58 8 9 0 24 3 1 8 1 2 1 4 43 2 60 7 42 23 1 32 54 2 1 17 1 6 10
    1 29 2 56 0 1 6 2 1 2 2 9 0 10 1 26 0 2 2 56 0 1 4 4 1 2 2 3 0 30 1 3 0 11 2
    56 0 1 7 1 2 1 2 20 1 22 5 1 2 56 0 1 6 2 1 2 2 7 2 10 1 30 0 59 4 3 2 1 3 9
    0 40 4 55 0 1 6 1 2 1 3 7 1 11 1 29 2 56 0 1 6 1 2 1 3 7 1 11 1 15 0 12 3 55
    1 1 6 1 2 1 3 9 0 10 1 29 2 70 0 4 5 1 0 1 7 18 1 61 0 2 6 12 7 98 0 2 8 11
    6 73 1 27 0 1 0 1 0 4 1 49 19 1 1 5 10 1 35 9 0 100 19 23 3 4 2 1 2 2 6 3 3
    13 11 1 0 10 3 703 2 946 3 28 2 29 1 30 1 64 31 9 0 45 2 1 0 117 1 34 0 118
    11 4 11 219 4 57 9 1 28 2 0 48 30 49 4 47 16 38 8 12 2 30 12 56 13 48 19 152
    2 1 20 4 0 6 0 2 2 198 63 720 32 3070 2 141 0 96 31 554 5 105 1 30164 3 1 9
    32 1 80 1 272 0 3 0 4 0 23 4 4 0 83 1 50 17 26 17 13 0 38 7 25 12 44 3 47 13
    36 0 67 13 12 0 8 1 45 2 50 0 1 2 2 1 5 1 1 0 41 4 5 1 236 7 1 1 20272 0 737
    15 16 15 973 0 226 0 149 4 1670 2 1 1 5 3 40 2 4 0 165 1 573 3 387 1 80 2 70
    10 49 3 122 2 53 14 41 0 2 1 10 3 45 10 7 0 61 2 36 13 16 1 44 0 12 2 48 13
    8 3 1 1 92 11 6 0 2 0 157 11 21 3 55 1 1 6 2 1 2 2 9 0 10 1 2 6 3 4 192 17
    23 0 81 19 235 6 2 8 27 1 82 16 106 12 101 14 256 14 245 5 1 1 2 3 1 0 1 1
    141 6 2 6 3 0 28 9 40 6 1 3 8 0 9 10 46 15 405 7 1 7 82 21 1 13 122 5 3 0 1
    1 1 6 1 0 66 4 1 1 1 4 347 3 9 1 1 0 48 6 3 4 5373 0 6 14 13978 4 59 6 1048
    0 1 54 7 3 81 0 11 1 19627 1 4705 45 2 22 542 4 3 5 8 7 2 6 30 3 148 2 1979
    54 4 49 8 0 14 0 22 4 1 14 1360 6 1 16 2 6 1 1 1 4 100 0 160 6 375 0 61 3
    508 3 992 6 109 6 792501 239))

(defparameter tokenizer::*letter-ranges*
  (tokenizer::%expand-ranges (tokenizer::%letter-deltas)))

(defparameter tokenizer::*number-ranges*
  (tokenizer::%expand-ranges (tokenizer::%number-deltas)))

(defparameter tokenizer::*mark-ranges*
  (tokenizer::%expand-ranges (tokenizer::%mark-deltas)))
